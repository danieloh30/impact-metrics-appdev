package com.ibm.advocacy.service;

import com.ibm.advocacy.model.*;
import com.ibm.advocacy.model.Row;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.*;
import org.apache.poi.xssf.usermodel.*;
import java.io.*;
import java.time.*;
import java.time.format.TextStyle;
import java.util.*;
import static com.ibm.advocacy.model.MetricsSchema.*;

/** Upsert one monthly tab, retaining unmatched rows, unknown columns, other sheets, and formulas. */
@ApplicationScoped
public class MonthlyInputWorkbook {
    public static final String META="RUN publication";
    static final List<String> EXTRA=List.of("Starting baseline","Baseline as of","Snapshot reporting month","Snapshot stage","Source run","Measured at");
    static final Set<String> NUMERIC=Set.of("Current total","Previous total",VIEWS,"Weight","Impact hours","Starting baseline");
    public record Merge(byte[] bytes,String sheet,boolean created,int updated,int appended,int unchanged) {}
    static String canonical(String header){String h=WorkbookService.header(header);return switch(h){case "Date of activity"->"Date";case "Author of activity"->"Author";case "Name of event"->"Event";default->h;};}
    static Map<String,Integer> columns(org.apache.poi.ss.usermodel.Row head,DataFormatter format){Map<String,Integer> columns=new LinkedHashMap<>();for(Cell cell:head){String key=canonical(format.formatCellValue(cell));if(key.isBlank())continue;if(columns.putIfAbsent(key,cell.getColumnIndex())!=null)throw new BadRequestException("Duplicate monthly column: "+key);}return columns;}
    static int headerIndex(XSSFSheet sheet,DataFormatter format){for(int i=0;i<=Math.min(29,sheet.getLastRowNum());i++){var row=sheet.getRow(i);if(row!=null&&columns(row,format).keySet().containsAll(Set.of("Date","Author","Type of activity",URL)))return i;}throw new BadRequestException("Monthly sheet needs Date, Author, Type of activity and resource URL columns");}
    static Row read(org.apache.poi.ss.usermodel.Row row,Map<String,Integer> columns,DataFormatter format){Row value=new Row("excel:"+(row.getRowNum()+1),Map.of());for(var entry:columns.entrySet()){Cell cell=row.getCell(entry.getValue());String text=WorkbookCells.value(cell,format);if(entry.getKey().equals("Date")&&cell!=null&&(cell.getCellType()==CellType.NUMERIC||cell.getCellType()==CellType.FORMULA&&cell.getCachedFormulaResultType()==CellType.NUMERIC)&&DateUtil.isCellDateFormatted(cell))text=cell.getLocalDateTimeCellValue().toLocalDate().toString();if(entry.getKey().equals("Date")&&!text.isBlank())text=WorkbookService.parseDate(text,"monthly publication",row.getRowNum()+1).toString();value.cells.put(entry.getKey(),text);}return value;}
    static List<String> baseKey(Row row){return TrailingReporting.activityKey(row).subList(0,6);}
    public Merge merge(byte[] original,Data incoming,String month,String stage,String runId) throws IOException {
        if(original.length>6_000_000||incoming.rows.size()>20_000)throw new BadRequestException("Workbook publication exceeds the supported size");
        YearMonth selected=YearMonth.parse(month);
        try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(original));ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            List<XSSFSheet> candidates=new ArrayList<>();for(var sheet:book){int[] label=WorkbookService.monthlyLabel(sheet.getSheetName());if(label[0]==selected.getMonthValue()&&(label[1]==0||label[1]==selected.getYear()))candidates.add((XSSFSheet)sheet);}
            if(candidates.size()>1)throw new BadRequestException("Multiple monthly tabs match "+month+"; select one authoritative sheet before publishing");
            boolean created=candidates.isEmpty();XSSFSheet sheet=created?book.createSheet(selected.getMonth().getDisplayName(TextStyle.FULL,Locale.US)+" "+selected.getYear()+" inputs"):candidates.getFirst();
            if(book.isSheetHidden(book.getSheetIndex(sheet))||book.isSheetVeryHidden(book.getSheetIndex(sheet)))throw new BadRequestException("Cannot publish into a hidden monthly tab");
            DataFormatter format=new DataFormatter(Locale.US);format.setUseCachedValuesForFormulaCells(true);
            int header=created?0:headerIndex(sheet,format);var head=created?sheet.createRow(0):sheet.getRow(header);Map<String,Integer> columns=created?new LinkedHashMap<>():columns(head,format);
            List<String> required=new ArrayList<>(FINAL_HEADERS);required.addAll(EXTRA);
            for(String field:required)if(!columns.containsKey(field)){int column=Math.max(0,head.getLastCellNum());head.createCell(column).setCellValue(field);columns.put(field,column);}
            Map<List<String>,List<org.apache.poi.ss.usermodel.Row>> exact=new HashMap<>(),base=new HashMap<>();
            Set<List<String>> combinedAttributions=new HashSet<>();
            for(int i=header+1;i<=sheet.getLastRowNum();i++){var row=sheet.getRow(i);if(row==null)continue;Row data=read(row,columns,format);if(data.get("Name of activity").isBlank()||data.get("Date").isBlank())continue;if(Calculations.components(data.get("Author")).size()>1||Calculations.components(data.get("Tool")).size()>1)for(Row share:Calculations.split(data,null))combinedAttributions.add(baseKey(share));exact.computeIfAbsent(TrailingReporting.activityKey(data),k->new ArrayList<>()).add(row);base.computeIfAbsent(baseKey(data),k->new ArrayList<>()).add(row);}
            Set<List<String>> seen=new HashSet<>();int updated=0,appended=0,unchanged=0;
            CellStyle numeric=book.createCellStyle();numeric.setDataFormat(book.createDataFormat().getFormat("#,##0.######"));
            for(Row input:incoming.rows){Row data=input.copy();data.cells.put("Date",input.get("Date").isBlank()?input.get("Date of activity"):input.get("Date"));data.cells.put("Author",input.get("Author").isBlank()?input.get("Author of activity"):input.get("Author"));
                if(data.get("Date").isBlank()||data.get("Author").isBlank()||data.get("Tool").isBlank()||data.get("Name of activity").isBlank())throw new BadRequestException("Publication row needs a date, author, tool and activity name: "+input.id);
                List<String> key=TrailingReporting.activityKey(data);if(!seen.add(key))throw new BadRequestException("Duplicate incoming activity attribution: "+input.get("Name of activity"));
                if(combinedAttributions.contains(baseKey(data)))throw new BadRequestException("An existing monthly row combines authors or tools for "+data.get("Name of activity")+". Review its allocation before publishing; no duplicate shares were appended.");
                List<org.apache.poi.ss.usermodel.Row> matches=exact.getOrDefault(key,base.getOrDefault(baseKey(data),List.of()));if(matches.size()>1)throw new BadRequestException("Ambiguous existing activity attribution: "+input.get("Name of activity"));
                var target=matches.isEmpty()?sheet.createRow(sheet.getLastRowNum()+1):matches.getFirst();
                boolean carry=data.get("Snapshot stage").equals("BASELINE_CARRY");if(!matches.isEmpty()&&carry){unchanged++;continue;}
                data.cells.put("Snapshot reporting month",month);data.cells.put("Snapshot stage",carry?"BASELINE_CARRY":stage);data.cells.put("Source run",runId);
                boolean changed=false;for(var entry:columns.entrySet()){String field=entry.getKey(),value=data.get(field);if(value.isBlank())continue;Cell cell=target.getCell(entry.getValue());String old=WorkbookCells.value(cell,format);if(field.equals("Starting baseline")&&!old.isBlank())continue;if(old.equals(value))continue;if(cell==null)cell=target.createCell(entry.getValue());if(NUMERIC.contains(field)){try{cell.setCellValue(Calculations.number(value).doubleValue());if(created||matches.isEmpty())cell.setCellStyle(numeric);}catch(IllegalArgumentException invalid){throw new BadRequestException("Invalid publication number for "+field+" in "+data.get("Name of activity"));}}else cell.setCellValue(value);changed=true;}
                if(matches.isEmpty()){appended++;exact.put(key,List.of(target));base.computeIfAbsent(baseKey(data),k->new ArrayList<>()).add(target);}else if(changed)updated++;else unchanged++;
            }
            if(sheet.getLastRowNum()>20_000)throw new BadRequestException("Published monthly tab exceeds 20,000 rows");
            for(var table:sheet.getTables()){AreaReference area=table.getArea();if(area.getFirstCell().getRow()==header)table.setArea(new AreaReference(area.getFirstCell(),new CellReference(sheet.getLastRowNum(),Math.max(area.getLastCell().getCol(),columns.size()-1)),org.apache.poi.ss.SpreadsheetVersion.EXCEL2007));}
            if(created){sheet.createFreezePane(0,1);for(int i=0;i<columns.size();i++)sheet.setColumnWidth(i,24*256);}
            XSSFSheet metadata=book.getSheet(META);if(metadata==null){metadata=book.createSheet(META);var titles=metadata.createRow(0);String[] fields={"Reporting month","Stage","Run","Updated at"};for(int i=0;i<fields.length;i++)titles.createCell(i).setCellValue(fields[i]);}
            if(metadata.getRow(0)==null||!format.formatCellValue(metadata.getRow(0).getCell(0)).equals("Reporting month")||!format.formatCellValue(metadata.getRow(0).getCell(1)).equals("Stage"))throw new BadRequestException("The RUN publication tab has an incompatible layout; no workbook changes were published");
            int metadataRow=-1;for(int i=1;i<=metadata.getLastRowNum();i++)if(metadata.getRow(i)!=null&&format.formatCellValue(metadata.getRow(i).getCell(0)).equals(month))metadataRow=i;
            var mark=metadataRow<0?metadata.createRow(metadata.getLastRowNum()+1):metadata.getRow(metadataRow);String[] values={month,stage,runId,Instant.now().toString()};for(int i=0;i<values.length;i++){Cell cell=mark.getCell(i);if(cell==null)cell=mark.createCell(i);cell.setCellValue(values[i]);}
            book.write(out);byte[] bytes=out.toByteArray();if(bytes.length>6_000_000)throw new BadRequestException("Updated workbook exceeds 6 MB");return new Merge(bytes,sheet.getSheetName(),created,updated,appended,unchanged);
        }
    }
}
