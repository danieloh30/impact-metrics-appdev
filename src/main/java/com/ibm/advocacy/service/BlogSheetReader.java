package com.ibm.advocacy.service;

import com.ibm.advocacy.model.Data;
import com.ibm.advocacy.model.Row;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import org.apache.poi.ss.usermodel.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import static com.ibm.advocacy.model.MetricsSchema.*;

/** Source-specific blog adapters; raw activity-sheet aliases stay separate. */
@ApplicationScoped
public class BlogSheetReader {
    public record Result(Data data, Map<String,YearMonth> months, int undatedRows) {}
    public static boolean matches(String name) {
        String key=name.replace('\u00a0',' ').strip().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
        return Set.of("markus blog data","elder blog data").contains(key);
    }
    private String header(String value) {
        return switch(value.strip().toLowerCase(Locale.ROOT)) {
            case "date", "month" -> "Date of activity";
            case "name" -> "Author of activity";
            case "title" -> "Name of activity";
            case "type" -> "Type of activity";
            case "link", "blog" -> URL;
            case "views" -> VIEWS;
            case "current" -> "Current total";
            case "previous" -> "Previous total";
            case "trailing" -> "Trailing 90-day";
            case "tool" -> "Tool";
            case "strategic initiative" -> "Strategic initiative";
            case "event" -> "Event";
            case "collection" -> "Collection";
            default -> value.strip();
        };
    }
    public Result read(Sheet sheet) {
        if(sheet.getLastRowNum()>20_000)throw new BadRequestException("Sheet exceeds 20,000 rows: "+sheet.getSheetName());
        DataFormatter formatter=new DataFormatter(Locale.US);formatter.setUseCachedValuesForFormulaCells(true);
        List<String> headers=null;int first=-1;boolean monthColumn=false;
        for(int n=0;n<=Math.min(29,sheet.getLastRowNum());n++) {
            var source=sheet.getRow(n);if(source==null)continue;
            if(source.getLastCellNum()>100)throw new BadRequestException("Sheet exceeds 100 columns");
            List<String> candidate=new ArrayList<>();
            for(int c=0;c<source.getLastCellNum();c++)candidate.add(header(formatter.formatCellValue(source.getCell(c))));
            if(candidate.contains("Date of activity")&&candidate.contains("Author of activity")&&candidate.contains("Name of activity")&&candidate.contains(URL)&&candidate.contains(VIEWS)) {headers=candidate;first=n;monthColumn=formatter.formatCellValue(source.getCell(candidate.indexOf("Date of activity"))).strip().equalsIgnoreCase("Month");break;}
        }
        if(headers==null)throw new BadRequestException("Blog sheet needs Date or Month, Name, Title, Views, and Link or blog headers in the first 30 rows");
        Set<String> seen=new HashSet<>();
        for(int c=0;c<headers.size();c++) {
            if(headers.get(c).isBlank())headers.set(c,"Unnamed column "+(c+1));
            if(!seen.add(headers.get(c)))throw new BadRequestException("Duplicate blog header after normalization: "+headers.get(c));
        }
        List<Row> rows=new ArrayList<>();Map<String,YearMonth> months=new LinkedHashMap<>();int undated=0;
        for(int n=first+1;n<=sheet.getLastRowNum();n++) {
            var source=sheet.getRow(n);if(source==null)continue;
            if(source.getLastCellNum()>100)throw new BadRequestException("Sheet exceeds 100 columns");
            Row row=new Row(sheet.getSheetName()+":"+(n+1),Map.of());
            for(int c=0;c<Math.max(headers.size(),source.getLastCellNum());c++) {
                Cell cell=source.getCell(c);String value=formatter.formatCellValue(cell);
                if(c>=headers.size()) {if(!value.isBlank())throw new BadRequestException("Data without a column header in "+sheet.getSheetName()+" row "+(n+1));continue;}
                if(cell!=null&&(cell.getCellType()==CellType.ERROR||(cell.getCellType()==CellType.FORMULA&&cell.getCachedFormulaResultType()==CellType.ERROR)))throw new BadRequestException("Excel error at "+sheet.getSheetName()+" row "+(n+1));
                if(cell!=null&&(cell.getCellType()==CellType.NUMERIC||(cell.getCellType()==CellType.FORMULA&&cell.getCachedFormulaResultType()==CellType.NUMERIC))&&DateUtil.isCellDateFormatted(cell))value=cell.getLocalDateTimeCellValue().toLocalDate().toString();
                row.cells.put(headers.get(c),value);
            }
            if(row.cells.values().stream().allMatch(String::isBlank))continue;
            String date=row.get("Date of activity").strip();
            if(date.isBlank()){undated++;continue;}
            LocalDate parsed=date(date,sheet.getSheetName(),n+1);
            // Month-only values use the first day as a grouping date, with explicit precision provenance.
            row.cells.put("Date of activity",parsed.toString());
            if(monthColumn||isMonthOnly(date)) {row.cells.put("Date of activity",YearMonth.from(parsed).atDay(1).toString());row.cells.put("Source date precision","Month only");}
            if(row.get("Type of activity").isBlank())row.cells.put("Type of activity","Blog post / slide shares / .io updates");
            row.cells.put("Source sheet",sheet.getSheetName());row.cells.put("Source row",Integer.toString(n+1));
            rows.add(row);months.put(row.id,YearMonth.from(parsed));
        }
        LinkedHashSet<String> output=new LinkedHashSet<>(headers);output.addAll(List.of("Type of activity","Source sheet","Source row","Source date precision"));
        return new Result(new Data(new ArrayList<>(output),rows),months,undated);
    }
    private static final List<DateTimeFormatter> MONTHS=List.of(DateTimeFormatter.ofPattern("uuuu-MM"),
        new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("MMM uuuu").toFormatter(Locale.US),
        new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("MMMM uuuu").toFormatter(Locale.US));
    private static boolean isMonthOnly(String text) {
        for(var pattern:MONTHS)try{YearMonth.parse(text,pattern);return true;}catch(DateTimeParseException ignored){}
        return false;
    }
    private static LocalDate date(String text,String sheet,int row) {
        for(var pattern:List.of(DateTimeFormatter.ISO_LOCAL_DATE,DateTimeFormatter.ofPattern("M/d/uuuu").withResolverStyle(ResolverStyle.STRICT),DateTimeFormatter.ofPattern("M/d/uu").withResolverStyle(ResolverStyle.STRICT)))try{return LocalDate.parse(text,pattern);}catch(DateTimeParseException ignored){}
        for(var pattern:MONTHS)try{return YearMonth.parse(text,pattern).atDay(1);}catch(DateTimeParseException ignored){}
        throw new BadRequestException("Invalid blog Date or Month in "+sheet+" row "+row+": "+text+". Use an Excel date, YYYY-MM-DD, M/D/YYYY, YYYY-MM or September 2026.");
    }
}
