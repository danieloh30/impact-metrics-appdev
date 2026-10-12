package com.ibm.advocacy.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Published AppDev monthly impact totals are not weighted or allocated again. */
@ApplicationScoped
public class ImpactTrends {
    public record Series(String name,List<String> values) {}
    public record Chart(List<String> months,List<Series> series,List<Boolean> available) {}
    public record Snapshot(String endMonth,Chart channel,Chart tool,List<String> warnings) {}
    public record Difference(String name,String published,String recalculated,String difference) {}
    public record Comparison(String publishedTotal,String recalculatedTotal,String difference,List<Difference> rows) {}
    public record PublishedComparison(String month,Comparison channel,Comparison tool) {}
    public record Timeline(Chart channel,Chart tool,Map<String,Object> source,List<String> warnings,PublishedComparison comparison) {}
    public static final String SHEET="AppDev Advocacy";
    public static final YearMonth START=YearMonth.of(2025,9);
    public Snapshot read(byte[] bytes,String filename,String endMonth) throws IOException {
        if(bytes==null||bytes.length==0||bytes.length>6_000_000)throw new BadRequestException("Chart history workbook must be smaller than 6 MB");
        try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet=book.getSheet(SHEET);if(sheet==null||book.isSheetHidden(book.getSheetIndex(sheet))||book.isSheetVeryHidden(book.getSheetIndex(sheet)))throw new BadRequestException("Chart history needs the visible AppDev Advocacy sheet");
            DataFormatter formatter=new DataFormatter(Locale.US);formatter.setUseCachedValuesForFormulaCells(true);
            org.apache.poi.ss.usermodel.Row channels=header(sheet,"Type of activity",formatter),tools=header(sheet,"Tool",formatter);
            List<Integer> channelMonths=months(channels,formatter),toolMonths=months(tools,formatter);
            if(!channelMonths.equals(toolMonths))throw new BadRequestException("Activity and tool month headings must match in AppDev Advocacy");
            YearMonth ending;
            if(endMonth!=null&&!endMonth.isBlank())try{ending=YearMonth.parse(endMonth);}catch(Exception invalid){throw new BadRequestException("Source ending month must be YYYY-MM");}
            else {var match=java.util.regex.Pattern.compile("(?<![0-9])(20[0-9]{2})(?![0-9])").matcher(filename);if(!match.find())throw new BadRequestException("Month-only headings need a source ending month; the filename has no reporting year");int year=Integer.parseInt(match.group(1));if(match.find())throw new BadRequestException("Ambiguous workbook year; set its source ending month explicitly");ending=YearMonth.of(year,channelMonths.getLast());}
            if(ending.getMonthValue()!=channelMonths.getLast())throw new BadRequestException("Source ending month must match the last month heading");
            List<String> dates=new ArrayList<>();for(int i=0;i<channelMonths.size();i++){YearMonth month=ending.minusMonths(channelMonths.size()-1-i);if(month.getMonthValue()!=channelMonths.get(i))throw new BadRequestException("AppDev Advocacy month headings must be consecutive; missing or duplicated months need correction");dates.add(month.toString());}
            Chart channel=table(sheet,channels,dates,formatter),tool=table(sheet,tools,dates,formatter);List<String> warnings=new ArrayList<>();
            for(int i=0;i<dates.size();i++) {BigDecimal a=total(channel,i),b=total(tool,i);if(a.subtract(b).abs().compareTo(new BigDecimal("0.000001"))>0)warnings.add(dates.get(i)+" published activity/tool totals differ: "+Calculations.format(a)+" vs "+Calculations.format(b)+" impact hours. Values are preserved.");}
            return new Snapshot(ending.toString(),channel,tool,List.copyOf(warnings));
        }catch(BadRequestException invalid){throw invalid;}catch(Exception invalid){throw new BadRequestException("Cannot read chart history: "+invalid.getMessage());}
    }
    private static org.apache.poi.ss.usermodel.Row header(Sheet sheet,String name,DataFormatter formatter) {
        List<org.apache.poi.ss.usermodel.Row> matches=new ArrayList<>();for(int i=0;i<=Math.min(sheet.getLastRowNum(),200);i++){var row=sheet.getRow(i);if(row!=null&&text(row.getCell(0),formatter).strip().equalsIgnoreCase(name))matches.add(row);}
        if(matches.size()!=1)throw new BadRequestException("Expected one "+name+" monthly table in AppDev Advocacy");return matches.getFirst();
    }
    private static String text(Cell cell,DataFormatter formatter){return cell==null?"":WorkbookCells.value(cell,formatter);}
    private static List<Integer> months(org.apache.poi.ss.usermodel.Row head,DataFormatter formatter) {
        List<Integer> values=new ArrayList<>();for(int c=1;c<head.getLastCellNum();c++){String value=text(head.getCell(c),formatter).strip();if(value.isBlank())break;try{values.add(Month.valueOf(value.toUpperCase(Locale.ROOT)).getValue());}catch(Exception invalid){throw new BadRequestException("Unexpected month heading in AppDev Advocacy: "+value);}}
        if(values.isEmpty()||values.size()>60)throw new BadRequestException("AppDev Advocacy needs 1–60 monthly columns");return values;
    }
    private static Chart table(Sheet sheet,org.apache.poi.ss.usermodel.Row head,List<String> dates,DataFormatter formatter) {
        List<Series> rows=new ArrayList<>();Set<String> names=new HashSet<>();
        for(int r=head.getRowNum()+1;r<=Math.min(sheet.getLastRowNum(),head.getRowNum()+100);r++) {
            var row=sheet.getRow(r);if(row==null)break;String name=text(row.getCell(0),formatter).strip();if(name.isBlank()||name.equalsIgnoreCase("total")||name.equalsIgnoreCase("grand total"))break;
            if(!names.add(name.toLowerCase(Locale.ROOT)))throw new BadRequestException("Duplicate historical chart category: "+name);
            List<String> values=new ArrayList<>();for(int c=1;c<=dates.size();c++){String value=text(row.getCell(c),formatter);try{values.add(value.isBlank()?"0":Calculations.format(Calculations.number(value)));}catch(IllegalArgumentException invalid){throw new BadRequestException("Invalid historical impact at "+SHEET+"!"+new org.apache.poi.ss.util.CellReference(r,c).formatAsString());}}
            rows.add(new Series(name,List.copyOf(values)));
        }
        if(rows.isEmpty())throw new BadRequestException("Empty monthly impact table in AppDev Advocacy");return new Chart(List.copyOf(dates),List.copyOf(rows),Collections.nCopies(dates.size(),true));
    }
    private static BigDecimal total(Chart chart,int i){return chart.series().stream().map(row->Calculations.number(row.values().get(i))).reduce(BigDecimal.ZERO,BigDecimal::add);}
    public static Timeline build(String selected,List<MonthlyReporting.Aggregate> channel,List<MonthlyReporting.Aggregate> tool,Snapshot history,Map<String,Object> source) {
        return build(selected,channel,tool,history,source,true);
    }
    public static Timeline build(String selected,List<MonthlyReporting.Aggregate> channel,List<MonthlyReporting.Aggregate> tool,Snapshot history,Map<String,Object> source,boolean currentAvailable) {
        YearMonth month=YearMonth.parse(selected);List<String> dates=new ArrayList<>();YearMonth first=month.isBefore(START)?month:START;
        if(java.time.temporal.ChronoUnit.MONTHS.between(first,month)>120)throw new BadRequestException("Chart reporting month must be within ten years of September 2025");
        for(YearMonth point=first;!point.isAfter(month);point=point.plusMonths(1))dates.add(point.toString());
        List<String> warnings=new ArrayList<>();if(history==null)warnings.add("Published history since September 2025 is unavailable until the chart workbook is read.");
        else {history.warnings().stream().filter(warning->dates.subList(0,dates.size()-1).stream().anyMatch(warning::startsWith)).forEach(warnings::add);warnings.add("Published monthly impact is imported as-is. Month-only headings are anchored to "+history.endMonth()+"; the selected month is replaced by this run.");}
        if(!currentAvailable)warnings.add("No eligible selected-month contribution is available; that point is shown as a gap.");
        return new Timeline(merge(dates,channel,history==null?null:history.channel(),currentAvailable),merge(dates,tool,history==null?null:history.tool(),currentAvailable),source,List.copyOf(warnings),history==null||!history.channel().months().contains(selected)||!currentAvailable?null:new PublishedComparison(selected,compare(selected,channel,history.channel()),compare(selected,tool,history.tool())));
    }
    private static Comparison compare(String selected,List<MonthlyReporting.Aggregate> current,Chart history){
        int index=history.months().indexOf(selected);Map<String,String> labels=new TreeMap<>();Map<String,BigDecimal> published=new HashMap<>(),recalculated=new HashMap<>();
        for(Series row:history.series()){String key=row.name().toLowerCase(Locale.ROOT);labels.put(key,row.name());published.put(key,Calculations.number(row.values().get(index)));}
        for(var row:current){String key=row.name().toLowerCase(Locale.ROOT);labels.putIfAbsent(key,row.name());recalculated.put(key,Calculations.number(row.impact()));}
        List<Difference> rows=new ArrayList<>();BigDecimal oldTotal=BigDecimal.ZERO,newTotal=BigDecimal.ZERO;
        for(var label:labels.entrySet()){BigDecimal old=published.getOrDefault(label.getKey(),BigDecimal.ZERO),now=recalculated.getOrDefault(label.getKey(),BigDecimal.ZERO);oldTotal=oldTotal.add(old);newTotal=newTotal.add(now);rows.add(new Difference(label.getValue(),Calculations.format(old),Calculations.format(now),Calculations.format(now.subtract(old))));}
        return new Comparison(Calculations.format(oldTotal),Calculations.format(newTotal),Calculations.format(newTotal.subtract(oldTotal)),List.copyOf(rows));
    }
    private static Chart merge(List<String> dates,List<MonthlyReporting.Aggregate> current,Chart history,boolean currentAvailable) {
        Map<String,String> labels=new LinkedHashMap<>();if(history!=null)history.series().forEach(row->labels.putIfAbsent(row.name().toLowerCase(Locale.ROOT),row.name()));current.forEach(row->labels.putIfAbsent(row.name().toLowerCase(Locale.ROOT),row.name()));
        List<Boolean> available=new ArrayList<>();for(int i=0;i<dates.size()-1;i++)available.add(history!=null&&history.months().contains(dates.get(i)));available.add(currentAvailable);
        List<Series> series=new ArrayList<>();for(var label:labels.entrySet()) {
            List<String> values=new ArrayList<>();for(int i=0;i<dates.size()-1;i++) {
                int index=history==null?-1:history.months().indexOf(dates.get(i));if(index<0){values.add(null);continue;}
                var row=history.series().stream().filter(value->value.name().equalsIgnoreCase(label.getValue())).findFirst();values.add(row.isPresent()?row.get().values().get(index):"0");
            }
            var row=current.stream().filter(value->value.name().equalsIgnoreCase(label.getValue())).findFirst();values.add(currentAvailable?row.map(MonthlyReporting.Aggregate::impact).orElse("0"):null);
            series.add(new Series(label.getValue(),Collections.unmodifiableList(values)));
        }
        return new Chart(List.copyOf(dates),List.copyOf(series),List.copyOf(available));
    }
}
