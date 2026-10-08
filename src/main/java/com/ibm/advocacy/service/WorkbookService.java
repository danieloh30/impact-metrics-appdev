package com.ibm.advocacy.service;

import com.ibm.advocacy.model.Data;
import com.ibm.advocacy.model.PreparedWorkbook;
import com.ibm.advocacy.model.WorkbookRequest;
import com.ibm.advocacy.model.Row;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import static com.ibm.advocacy.model.MetricsSchema.*;

/** Deterministic workbook extraction. No formulas, macros or external links are executed. */
@ApplicationScoped
public class WorkbookService {
    public record SheetInfo(String name, boolean eligible, int rows, List<String> months, String reason) {}
    public record Inspection(List<SheetInfo> sheets, String suggestedMonth, String suggestedCurrentSheet) {}
    private record SheetData(Data data, Map<String,LocalDate> dates) {}
    private static final List<DateTimeFormatter> DATES=List.of(DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("M/d/uuuu").withResolverStyle(ResolverStyle.STRICT),
        DateTimeFormatter.ofPattern("M/d/uu").withResolverStyle(ResolverStyle.STRICT));

    private XSSFWorkbook open(byte[] bytes) {
        if(bytes==null||bytes.length==0||bytes.length>6_000_000)throw new BadRequestException("Workbook must be smaller than 6 MB");
        try {
            XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(bytes));
            if(book.getNumberOfSheets()>100) { book.close();throw new BadRequestException("Workbook exceeds 100 sheets"); }
            return book;
        } catch(BadRequestException e) { throw e; }
        catch(Exception e) { throw new BadRequestException("Cannot read workbook. Upload an unencrypted .xlsx file."); }
    }
    public Inspection inspect(byte[] bytes) throws IOException {
        List<SheetInfo> sheets=new ArrayList<>();String latest="",suggested="";
        try(XSSFWorkbook book=open(bytes)) {
            for(Sheet sheet:book) {
                if(book.isSheetHidden(book.getSheetIndex(sheet))||book.isSheetVeryHidden(book.getSheetIndex(sheet))) {
                    sheets.add(new SheetInfo(sheet.getSheetName(),false,0,List.of(),"Hidden sheet — excluded"));continue;
                }
                try {
                    SheetData data=read(sheet);List<String> months=data.dates.values().stream().map(d->YearMonth.from(d).toString()).distinct().sorted().toList();
                    sheets.add(new SheetInfo(sheet.getSheetName(),true,data.data.rows.size(),months,""));
                    if(!months.isEmpty()&&months.getLast().compareTo(latest)>0){latest=months.getLast();suggested=sheet.getSheetName();}
                } catch(BadRequestException e) { sheets.add(new SheetInfo(sheet.getSheetName(),false,0,List.of(),e.getMessage())); }
            }
        }
        if(latest.isEmpty())throw new BadRequestException("No usable activity sheets found. Each needs Date of activity (or Date), Author, Type of activity and Link / URL to resource headers.");
        return new Inspection(sheets,latest,suggested);
    }
    public PreparedWorkbook prepare(WorkbookRequest request) throws IOException {
        YearMonth month;try {month=YearMonth.parse(request.month());}catch(Exception e){throw new BadRequestException("Choose a reporting month (YYYY-MM)");}
        LocalDate end=month.atEndOfMonth(),start=month.minusMonths(2).atDay(1);
        List<String> history=request.historySheets()==null?List.of():request.historySheets();
        if(new HashSet<>(history).size()!=history.size())throw new BadRequestException("Select each history sheet only once");
        List<Row> past=new ArrayList<>();LinkedHashSet<String> pastHeaders=new LinkedHashSet<>();int missing=0;
        try(XSSFWorkbook book=open(request.bytes())) {
            SheetData current=selected(book,request.currentSheet());
            List<Row> rows=current.data.rows.stream().filter(r->YearMonth.from(current.dates.get(r.id)).equals(month)).map(Row::copy).toList();
            if(rows.isEmpty())throw new BadRequestException("Selected current sheet has no activities in "+month);
            for(String name:history) {
                SheetData data=name.equals(request.currentSheet())?current:selected(book,name);
                pastHeaders.addAll(data.data.headers);
                for(Row original:data.data.rows) {
                    LocalDate date=data.dates.get(original.id);
                    if(date.isBefore(start)||date.isAfter(end))continue;
                    Row row=original.copy();
                    row.cells.put("Source sheet",name);
                    String baseline=row.get("Current total");
                    try { if(Calculations.number(baseline).signum()<0)throw new IllegalArgumentException(); }
                    catch(IllegalArgumentException e) { row.cells.put("Current total","Missing or invalid baseline — manual review required");missing++; }
                    // A raw submission can have multiple authors/tools; split its recorded baseline conservatively.
                    List<Row> split=Calculations.split(row,null);
                    if(split.size()>1&&!row.get("Current total").startsWith("Missing or invalid")) {
                        var allocations=Calculations.allocate(Calculations.number(baseline),split.size());
                        for(int i=0;i<split.size();i++)split.get(i).cells.put("Current total",Calculations.format(allocations.get(i)));
                    }
                    past.addAll(split);
                }
            }
            if(past.size()>20_000)throw new BadRequestException("Generated trailing input exceeds 20,000 rows");
            pastHeaders.addAll(List.of("Trailing 90-day","Current total","Source sheet"));
            return new PreparedWorkbook(new Data(current.data.headers,rows),past.isEmpty()?null:new Data(new ArrayList<>(pastHeaders),past),month.toString(),start.toString(),end.toString(),List.copyOf(history),missing);
        }
    }
    private SheetData selected(XSSFWorkbook book,String name) {
        Sheet sheet=name==null?null:book.getSheet(name);
        if(sheet==null)throw new BadRequestException("Unknown activity sheet: "+name);
        int index=book.getSheetIndex(sheet);
        if(book.isSheetHidden(index)||book.isSheetVeryHidden(index))throw new BadRequestException("Hidden sheets cannot be imported");
        return read(sheet);
    }
    private SheetData read(Sheet sheet) {
        if(sheet.getLastRowNum()>20_000)throw new BadRequestException("Sheet exceeds 20,000 rows: "+sheet.getSheetName());
        DataFormatter formatter=new DataFormatter(Locale.US);formatter.setUseCachedValuesForFormulaCells(true);
        List<String> headers=null;int headerIndex=-1;
        for(int n=0;n<=Math.min(29,sheet.getLastRowNum());n++) {
            org.apache.poi.ss.usermodel.Row row=sheet.getRow(n);if(row==null)continue;
            List<String> candidate=new ArrayList<>();
            if(row.getLastCellNum()>100)throw new BadRequestException("Sheet exceeds 100 columns");
            for(int c=0;c<row.getLastCellNum();c++)candidate.add(formatter.formatCellValue(row.getCell(c)).replace('\u00a0',' ').strip());
            if(candidate.contains("Type of activity")&&candidate.contains(URL)&&(candidate.contains("Author")||candidate.contains("Author of activity"))&&(candidate.contains("Date")||candidate.contains("Date of activity"))) {
                headers=candidate;headerIndex=n;break;
            }
        }
        if(headers==null)throw new BadRequestException("No activity headers found in first 30 rows");
        while(!headers.isEmpty()&&headers.getLast().isBlank())headers.removeLast();
        Set<String> seen=new HashSet<>();for(int c=0;c<headers.size();c++) {
            String h=headers.get(c);if(h.isBlank()){h="Unnamed column "+(c+1);headers.set(c,h);}
            if(!seen.add(h))throw new BadRequestException("Duplicate header: "+h);
        }
        String dateHeader=headers.contains("Date of activity")?"Date of activity":"Date";
        List<Row> result=new ArrayList<>();Map<String,LocalDate> dates=new LinkedHashMap<>();
        for(int n=headerIndex+1;n<=sheet.getLastRowNum();n++) {
            org.apache.poi.ss.usermodel.Row source=sheet.getRow(n);if(source==null)continue;
            if(source.getLastCellNum()>100)throw new BadRequestException("Sheet exceeds 100 columns");
            for(int c=headers.size();c<source.getLastCellNum();c++)if(!formatter.formatCellValue(source.getCell(c)).isBlank())throw new BadRequestException("Data without a column header in "+sheet.getSheetName()+" row "+(n+1));
            Row row=new Row(sheet.getSheetName()+":"+(n+1),Map.of());
            for(int c=0;c<headers.size();c++) {
                Cell cell=source.getCell(c);String value=formatter.formatCellValue(cell);
                if(cell!=null&&((cell.getCellType()==CellType.NUMERIC)||(cell.getCellType()==CellType.FORMULA&&cell.getCachedFormulaResultType()==CellType.NUMERIC))&&DateUtil.isCellDateFormatted(cell))value=cell.getLocalDateTimeCellValue().toLocalDate().toString();
                if(cell!=null&&(cell.getCellType()==CellType.ERROR||(cell.getCellType()==CellType.FORMULA&&cell.getCachedFormulaResultType()==CellType.ERROR)))throw new BadRequestException("Excel error at "+sheet.getSheetName()+" row "+(n+1));
                row.cells.put(headers.get(c),value);
            }
            if(row.cells.values().stream().allMatch(String::isBlank))continue;
            LocalDate date=parseDate(row.get(dateHeader),sheet.getSheetName(),n+1);
            row.cells.put(dateHeader,date.toString());result.add(row);dates.put(row.id,date);
        }
        return new SheetData(new Data(headers,result),dates);
    }
    private LocalDate parseDate(String value,String sheet,int row) {
        String text=value.strip();for(DateTimeFormatter pattern:DATES)try{return LocalDate.parse(text,pattern);}catch(DateTimeParseException ignored){}
        throw new BadRequestException("Invalid or missing activity date in "+sheet+" row "+row+": "+value+". Use an Excel date, YYYY-MM-DD or M/D/YYYY.");
    }
}
