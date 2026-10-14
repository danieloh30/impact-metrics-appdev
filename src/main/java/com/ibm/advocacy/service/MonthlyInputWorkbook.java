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
    static final List<String> EXTRA=List.of("Starting baseline","Snapshot as of","Baseline as of","Snapshot reporting month","Snapshot stage","Source run","Measured at","Verified reporting month","Activity stage","Reporting disposition");
    static final Set<String> NUMERIC=Set.of("Current total","Previous total",VIEWS,"Weight","Impact hours","Starting baseline");
    public record Merge(byte[] bytes,String sheet,boolean created,int updated,int appended,int unchanged) {}
    static String canonical(String header){String h=WorkbookService.header(header);return switch(h){case "Date of activity"->"Date";case "Author of activity"->"Author";case "Name of event"->"Event";default->h;};}
    static Map<String,Integer> columns(org.apache.poi.ss.usermodel.Row head,DataFormatter format){Map<String,Integer> columns=new LinkedHashMap<>();for(Cell cell:head){String key=canonical(format.formatCellValue(cell));if(key.isBlank())continue;if(columns.putIfAbsent(key,cell.getColumnIndex())!=null)throw new BadRequestException("Duplicate monthly column: "+key);}return columns;}
    static int headerIndex(XSSFSheet sheet,DataFormatter format){for(int i=0;i<=Math.min(29,sheet.getLastRowNum());i++){var row=sheet.getRow(i);if(row!=null&&columns(row,format).keySet().containsAll(Set.of("Date","Author","Type of activity",URL)))return i;}throw new BadRequestException("Monthly sheet needs Date, Author, Type of activity and resource URL columns");}
    static Row read(org.apache.poi.ss.usermodel.Row row,Map<String,Integer> columns,DataFormatter format){Row value=new Row("excel:"+(row.getRowNum()+1),Map.of());for(var entry:columns.entrySet()){Cell cell=row.getCell(entry.getValue());String text=WorkbookCells.value(cell,format);if(entry.getKey().equals("Date")&&cell!=null&&(cell.getCellType()==CellType.NUMERIC||cell.getCellType()==CellType.FORMULA&&cell.getCachedFormulaResultType()==CellType.NUMERIC)&&DateUtil.isCellDateFormatted(cell))text=cell.getLocalDateTimeCellValue().toLocalDate().toString();if(entry.getKey().equals("Date")&&!text.isBlank())text=WorkbookService.parseDate(text,"monthly publication",row.getRowNum()+1).toString();value.cells.put(entry.getKey(),text);}return value;}
    static List<String> baseKey(Row row){List<String> key=new ArrayList<>(TrailingReporting.activityKey(row));key.remove(6);return key;}
    public Merge merge(byte[] original,Data incoming,String month,String stage,String runId) throws IOException {
        if(incoming.rows.size()>20_000)throw new BadRequestException("Workbook publication exceeds 20,000 rows");
        YearMonth selected=YearMonth.parse(month);
        try(XSSFWorkbook book=WorkbookLimits.open(original,"Publication workbook");ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            List<XSSFSheet> candidates=new ArrayList<>();for(var sheet:book){int[] label=WorkbookService.monthlyLabel(sheet.getSheetName());if(label[0]==selected.getMonthValue()&&(label[1]==0||label[1]==selected.getYear()))candidates.add((XSSFSheet)sheet);}
            if(candidates.size()>1)throw new BadRequestException("Multiple monthly tabs match "+month+"; select one authoritative sheet before publishing");
            boolean created=candidates.isEmpty();XSSFSheet sheet=created?book.createSheet(selected.getMonth().getDisplayName(TextStyle.FULL,Locale.US)+" "+selected.getYear()+" inputs"):candidates.getFirst();
            if(created) {
                YearMonth previous=selected.minusMonths(1);
                for(var existing:book) {int[] label=WorkbookService.monthlyLabel(existing.getSheetName());if(label[0]==previous.getMonthValue()&&(label[1]==0||label[1]==previous.getYear())) {book.setSheetOrder(sheet.getSheetName(),book.getSheetIndex(existing)+1);break;}}
            }
            if(book.isSheetHidden(book.getSheetIndex(sheet))||book.isSheetVeryHidden(book.getSheetIndex(sheet)))throw new BadRequestException("Cannot publish into a hidden monthly tab");
            DataFormatter format=new DataFormatter(Locale.US);format.setUseCachedValuesForFormulaCells(true);
            int header=created?0:headerIndex(sheet,format);var head=created?sheet.createRow(0):sheet.getRow(header);Map<String,Integer> columns=created?new LinkedHashMap<>():columns(head,format);
            for(var table:sheet.getTables()) {
                AreaReference area=table.getArea();Set<String> labels=new HashSet<>();var originalHead=sheet.getRow(area.getFirstCell().getRow());
                for(int c=area.getFirstCell().getCol();c<=area.getLastCell().getCol();c++) {
                    String label=originalHead==null?"":format.formatCellValue(originalHead.getCell(c));
                    if(label.isBlank()||!labels.add(label.toLowerCase(Locale.ROOT)))throw new BadRequestException("Existing Excel table has blank or duplicate column names. Repair the workbook table before publishing; no workbook changes were published.");
                }
            }
            List<String> required=new ArrayList<>(FINAL_HEADERS);required.addAll(EXTRA);
            for(String field:required)if(!columns.containsKey(field)){int column=Math.max(0,head.getLastCellNum());head.createCell(column).setCellValue(field);columns.put(field,column);}
            Map<List<String>,List<org.apache.poi.ss.usermodel.Row>> exact=new HashMap<>(),base=new HashMap<>();
            Set<List<String>> combinedAttributions=new HashSet<>();
            for(int i=header+1;i<=sheet.getLastRowNum();i++){var row=sheet.getRow(i);if(row==null)continue;Row data=read(row,columns,format);if(data.get("Reporting disposition").startsWith("Superseded")||data.get("Name of activity").isBlank()||data.get("Date").isBlank())continue;if(Calculations.components(data.get("Author")).size()>1||Calculations.components(data.get("Tool")).size()>1)for(Row share:Calculations.split(data,null))combinedAttributions.add(baseKey(share));exact.computeIfAbsent(TrailingReporting.activityKey(data),k->new ArrayList<>()).add(row);base.computeIfAbsent(baseKey(data),k->new ArrayList<>()).add(row);}
            Set<List<String>> seen=new HashSet<>();int updated=0,appended=0,unchanged=0;
            CellStyle numeric=book.createCellStyle();numeric.setDataFormat(book.createDataFormat().getFormat("#,##0.######"));
            for(Row input:incoming.rows){Row data=input.copy();data.cells.put("Date",input.get("Date").isBlank()?input.get("Date of activity"):input.get("Date"));data.cells.put("Author",input.get("Author").isBlank()?input.get("Author of activity"):input.get("Author"));
                if(data.get("Date").isBlank()||data.get("Author").isBlank()||data.get("Tool").isBlank()||data.get("Name of activity").isBlank())throw new BadRequestException("Publication row needs a date, author, tool and activity name: "+input.id);
                List<String> key=TrailingReporting.activityKey(data);if(!seen.add(key))throw new BadRequestException("Duplicate incoming activity attribution: "+input.get("Name of activity"));
                if(combinedAttributions.contains(baseKey(data)))throw new BadRequestException("An existing monthly row combines authors or tools for "+data.get("Name of activity")+". Review its allocation before publishing; no duplicate shares were appended.");
                List<org.apache.poi.ss.usermodel.Row> matches=exact.getOrDefault(key,base.getOrDefault(baseKey(data),List.of()));if(matches.size()>1)throw new BadRequestException("Ambiguous existing activity attribution: "+data.get("Name of activity")+". Sheet: "+sheet.getSheetName()+"; matching Excel rows: "+matches.stream().map(row->Integer.toString(row.getRowNum()+1)).collect(java.util.stream.Collectors.joining(", "))+". Author: "+data.get("Author")+"; Tool: "+data.get("Tool")+". Compare these rows and keep one copy per author/tool attribution if they are accidental duplicates. Different author/tool splits are valid and must be kept. Save the workbook, then preview again. No workbook changes were published.");
                var target=matches.isEmpty()?sheet.createRow(sheet.getLastRowNum()+1):matches.getFirst();
                boolean carry=data.get("Snapshot stage").equals("BASELINE_CARRY");if(!matches.isEmpty()&&carry){unchanged++;continue;}
                data.cells.put("Snapshot reporting month",month);data.cells.put("Snapshot stage",carry?"BASELINE_CARRY":stage);data.cells.put("Source run",runId);
                boolean changed=false;for(var entry:columns.entrySet()){String field=entry.getKey(),value=data.get(field);if(value.isBlank())continue;Cell cell=target.getCell(entry.getValue());String old=WorkbookCells.value(cell,format);if(field.equals("Starting baseline")&&!old.isBlank())continue;if(old.equals(value)&&!(NUMERIC.contains(field)&&cell!=null&&cell.getCellType()==CellType.FORMULA))continue;if(cell==null)cell=target.createCell(entry.getValue());if(NUMERIC.contains(field)){try{if(cell.getCellType()==CellType.FORMULA)cell.setBlank();cell.setCellValue(Calculations.number(value).doubleValue());if(created||matches.isEmpty())cell.setCellStyle(numeric);}catch(IllegalArgumentException invalid){throw new BadRequestException("Invalid publication number for "+field+" in "+data.get("Name of activity"));}}else cell.setCellValue(value);changed=true;}
                if(matches.isEmpty()){appended++;exact.put(key,List.of(target));base.computeIfAbsent(baseKey(data),k->new ArrayList<>()).add(target);}else if(changed)updated++;else unchanged++;
            }
            if(sheet.getLastRowNum()>20_000)throw new BadRequestException("Published monthly tab exceeds 20,000 rows");
            MonthlyRowSort.sort(sheet,header,columns,format);
            for(var table:sheet.getTables()){Set<String> names=new HashSet<>();for(var column:table.getCTTable().getTableColumns().getTableColumnArray())if(column.getName()==null||column.getName().isBlank()||!names.add(column.getName().toLowerCase(Locale.ROOT)))throw new BadRequestException("Existing Excel table has blank or duplicate column names. Repair the workbook table before publishing; no workbook changes were published.");AreaReference area=table.getArea();if(area.getFirstCell().getRow()==header)table.setArea(new AreaReference(area.getFirstCell(),new CellReference(sheet.getLastRowNum(),area.getLastCell().getCol()),org.apache.poi.ss.SpreadsheetVersion.EXCEL2007));}
            if(created){sheet.createFreezePane(0,1);for(int i=0;i<columns.size();i++)sheet.setColumnWidth(i,24*256);}
            XSSFSheet metadata=book.getSheet(META);if(metadata==null){metadata=book.createSheet(META);var titles=metadata.createRow(0);String[] fields={"Reporting month","Stage","Run","Updated at","As of"};for(int i=0;i<fields.length;i++)titles.createCell(i).setCellValue(fields[i]);}
            if(metadata.getRow(0)==null||!format.formatCellValue(metadata.getRow(0).getCell(0)).equals("Reporting month")||!format.formatCellValue(metadata.getRow(0).getCell(1)).equals("Stage"))throw new BadRequestException("The RUN publication tab has an incompatible layout; no workbook changes were published");
            int metadataRow=-1;for(int i=1;i<=metadata.getLastRowNum();i++)if(metadata.getRow(i)!=null&&format.formatCellValue(metadata.getRow(i).getCell(0)).equals(month))metadataRow=i;
            String asOf=incoming.rows.stream().map(row->row.get("Snapshot as of")).filter(value->!value.isBlank()).findFirst().orElse("");
            if(metadataRow>=0) {
                var previous=metadata.getRow(metadataRow);String previousDate=format.formatCellValue(previous.getCell(4));
                if(stage.equals("PROVISIONAL")&&format.formatCellValue(previous.getCell(1)).equals("COMPLETED"))throw new BadRequestException("A final snapshot is already published for "+month+". Start a new run rather than replacing it with an older provisional snapshot.");
                if(!asOf.isBlank()&&!previousDate.isBlank()&&LocalDate.parse(asOf).isBefore(LocalDate.parse(previousDate)))throw new BadRequestException("A newer snapshot is already published for "+month+" as of "+previousDate+". Start a new run to update it.");
            }
            if(metadata.getRow(0).getCell(4)==null)metadata.getRow(0).createCell(4).setCellValue("As of");
            var mark=metadataRow<0?metadata.createRow(metadata.getLastRowNum()+1):metadata.getRow(metadataRow);String[] values={month,stage,runId,Instant.now().toString(),asOf};for(int i=0;i<values.length;i++){Cell cell=mark.getCell(i);if(cell==null)cell=mark.createCell(i);cell.setCellValue(values[i]);}
            if(book.getNumberOfSheets()>100)throw new BadRequestException("Updated workbook exceeds 100 sheets");
            book.write(out);byte[] bytes=out.toByteArray();WorkbookLimits.checkArchive(bytes,"Updated workbook");return new Merge(bytes,sheet.getSheetName(),created,updated,appended,unchanged);
        }
    }
}
