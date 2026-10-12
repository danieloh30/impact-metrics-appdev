package com.ibm.advocacy.service;

import com.ibm.advocacy.model.*;
import com.ibm.advocacy.model.Row;
import com.ibm.advocacy.storage.RuntimeWorkspace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.ibm.advocacy.model.MetricsSchema.*;

/** Exact reporting calculations on copies of immutable agent results. */
@ApplicationScoped
public class MonthlyReporting {
    @Inject RuntimeWorkspace workspace;
    @Inject WorkbookService workbooks;
    @Inject ImpactTrends trends;
    public record Rule(String unit,String weight,String trailing) {}
    public record Context(Map<String,Rule> rules,Map<String,String> geography,Data snapshot,boolean sameMonth) {}
    public record Aggregate(String name,String currentImpact,String trailingImpact,String impact,String metrics,String unit,int rows) {}
    public record Report(String month,String cutoff,String scope,int revision,boolean complete,int included,int excluded,
                         String impact,List<Aggregate> activity,List<Aggregate> tool,List<Aggregate> bob,Data details,List<String> warnings,ImpactTrends.Timeline trends) {}
    static String key(String value) {return value.strip().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);}
    static boolean oneTime(Row row) {return row.id.toLowerCase(Locale.ROOT).startsWith("markus blog data:")||row.id.toLowerCase(Locale.ROOT).startsWith("elder blog data:")||BlogSheetReader.matches(row.get("Source sheet"));}
    static BigDecimal nonnegative(String value) {BigDecimal n=Calculations.number(value);if(n.signum()<0)throw new IllegalArgumentException("Negative metric");return n;}
    public Context context(Run run) throws Exception {
        Path file=workspace.runPath(run.id,"rawdata/trailing-original.xlsx");
        if(!Files.isRegularFile(file))return new Context(Map.of(),Map.of(),new Data(),false);
        Map<String,Rule> rules=new LinkedHashMap<>();Map<String,String> geography=new LinkedHashMap<>();
        byte[] bytes=Files.readAllBytes(file);
        try(XSSFWorkbook book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet model=book.getSheet("DA metric model 2026");
            if(model!=null) {
                DataFormatter formatter=new DataFormatter(Locale.US);formatter.setUseCachedValuesForFormulaCells(true);
                var head=model.getRow(0);Map<String,Integer> columns=new HashMap<>();
                if(head!=null)for(Cell cell:head)columns.put(key(formatter.formatCellValue(cell)),cell.getColumnIndex());
                if(!columns.keySet().containsAll(Set.of("activities","unit measure","weight","trailing 90 days?")))throw new BadRequestException("DA metric model 2026 needs Activities, Unit Measure, Weight and Trailing 90 days? columns");
                for(int i=1;i<=Math.min(model.getLastRowNum(),200);i++) {
                    var row=model.getRow(i);if(row==null)continue;
                    String activity=WorkbookCells.value(row.getCell(columns.get("activities")),formatter);
                    String weight=WorkbookCells.value(row.getCell(columns.get("weight")),formatter);
                    if(activity.isBlank()||weight.isBlank())continue;
                    try{nonnegative(weight);}catch(IllegalArgumentException invalid){throw new BadRequestException("Invalid model weight for "+activity);}
                    Rule rule=new Rule(WorkbookCells.value(row.getCell(columns.get("unit measure")),formatter),weight,WorkbookCells.value(row.getCell(columns.get("trailing 90 days?")),formatter));
                    if(rules.putIfAbsent(key(activity),rule)!=null)throw new BadRequestException("Duplicate activity in DA metric model 2026: "+activity);
                }
            }
            Sheet ranges=book.getSheet("Ranges");
            if(ranges!=null) {
                DataFormatter formatter=new DataFormatter(Locale.US);formatter.setUseCachedValuesForFormulaCells(true);
                for(int i=1;i<=Math.min(ranges.getLastRowNum(),1000);i++) {
                    var row=ranges.getRow(i);if(row==null)continue;
                    String author=WorkbookCells.value(row.getCell(7),formatter),region=WorkbookCells.value(row.getCell(8),formatter);
                    if(!author.isBlank()&&!region.isBlank()&&Set.of("AMER","EMEA","APAC","WW","GLOBAL").contains(region.toUpperCase(Locale.ROOT))) {
                        String old=geography.putIfAbsent(key(author),region);
                        if(old!=null&&!old.equals(region))throw new BadRequestException("Conflicting geography for "+author);
                    }
                }
            }
        }
        List<String> names=new ArrayList<>();Object selected=run.workbook.get("historySheets");
        if(selected instanceof List<?> list)list.forEach(n->names.add(String.valueOf(n)));
        String month=String.valueOf(run.workbook.getOrDefault("month",""));
        var inspections=workbooks.inspect(bytes).sheets();
        boolean sameMonth=names.size()==1&&inspections.stream().anyMatch(s->s.name().equals(names.getFirst())&&s.inputMonth()==YearMonth.parse(month).getMonthValue()&&(s.inputYear()==0||s.inputYear()==YearMonth.parse(month).getYear()));
        return new Context(rules,geography,names.isEmpty()?new Data():workbooks.reportingSnapshot(bytes,names),sameMonth);
    }
    static Map<List<String>,List<Row>> index(Data data) {
        Map<List<String>,List<Row>> result=new LinkedHashMap<>();
        for(Row row:data.rows)result.computeIfAbsent(TrailingReporting.activityKey(row),k->new ArrayList<>()).add(row);
        return result;
    }
    /** Preserve legacy processing exports; new SharePoint runs explicitly select month-end reporting. */
    public Data artifact(Run run,Data data,boolean current) throws Exception {
        if(!"MONTH_END".equals(run.workbook.get("reportingPolicy")))return data;
        Context context=context(run);
        Set<String> followup=new HashSet<>();
        if(current&&Files.exists(workspace.runPath(run.id,"state/phase-5-follow-up.json")))workspace.data(run.id,"phase-5-follow-up").rows.forEach(row->followup.add(row.id));
        return transform(run,data,current,context,followup);
    }
    static Data transform(Run run,Data data,boolean current,Context context,Set<String> followup) {
        String month=String.valueOf(run.workbook.getOrDefault("month",""));
        String cutoff=month.isBlank()?"":YearMonth.parse(month).atEndOfMonth().toString();
        Map<List<String>,List<Row>> lookup=index(context.snapshot());List<Row> rows=new ArrayList<>();
        for(Row original:data.rows) {
            Row row=original.copy();rows.add(row);Rule rule=context.rules().get(row.type());
            row.cells.putIfAbsent("Pre-report Views / attendance",original.get(VIEWS));
            row.cells.putIfAbsent("Pre-report Current total",original.get("Current total"));
            row.cells.putIfAbsent("Pre-report Previous total",original.get("Previous total"));
            row.cells.putIfAbsent("Pre-report Metric basis",original.get("Metric basis"));
            row.cells.putIfAbsent("Live collection note",original.get(NOTE));
            row.cells.putIfAbsent("Source workbook",String.valueOf(run.workbook.getOrDefault(current?"filename":"historyFilename","")));
            row.cells.putIfAbsent("Source sheet",original.id.contains(":")?original.id.split(":",2)[0]:current?String.valueOf(run.workbook.getOrDefault("currentSheet","")):"");
            row.cells.put("Source workbook URL",String.valueOf(run.workbook.getOrDefault(current?"sourceUrl":"historySourceUrl","")));
            row.cells.put("Source row",original.id.split("/",2)[0]);
            row.cells.put("Measured at",TrailingReporting.measuredAt(original));
            row.cells.put("Reporting month",month);row.cells.put("Activity ID",original.id);row.cells.put("Contribution",current?"Current":"Trailing growth");
            row.cells.put("Reporting status","Excluded");row.cells.put("Reporting reason","");row.cells.put("Reporting action","");row.cells.put("As of",cutoff);
            row.cells.put("Exact reporting metric","");row.cells.put("Impact hours","");row.cells.put("Weight",rule==null?"":rule.weight());row.cells.put("Metric unit",rule==null?"":rule.unit());
            row.cells.put("Trailing 90-day",oneTime(original)?"No":rule==null?"":rule.trailing());
            if(row.get("Geography").isBlank())row.cells.put("Geography",context.geography().getOrDefault(key(row.get("Author").isBlank()?row.get("Author of activity"):row.get("Author")),""));
            String reason="";BigDecimal metric=null;
            List<Row> matches=lookup.getOrDefault(TrailingReporting.activityKey(row),List.of());
            Row snapshot=context.sameMonth()&&matches.size()==1?matches.getFirst():null;
            MonthEndValue override=run.monthEndValues.get((current?"current:":"trailing:")+row.id);
            if(override!=null&&!override.asOf().equals(cutoff))override=null;
            boolean once=current&&oneTime(original);
            if(rule==null)reason="Activity has no rule in DA metric model 2026";
            else if(!cutoff.isBlank()&&!LocalDate.parse(cutoff).isBefore(LocalDate.now()))reason="Reporting month has not closed; month-end totals are not available yet";
            else if(current&&followup.contains(row.id)&&override==null)reason="Unresolved follow-up activity";
            else if(!current&&(oneTime(original)||!key(rule.trailing()).equals("yes")))reason="One-time activity is not trailing-eligible";
            else if(!current&&original.get("Metric basis").contains("not a trailing delta")&&override==null)reason="Retained historical input is not new growth";
            else try {
                if(once||current&&PERSON.contains(row.type())) {
                    metric=override!=null&&override.asOf().equals(cutoff)?nonnegative(override.value()):nonnegative(row.get("Pre-report Views / attendance"));row.cells.put("Month-end basis",override!=null?"User-confirmed month-end input":once?"One-time blog input for reporting month":"One-time attendance for reporting month");
                    row.cells.put("Current total",once?"":Calculations.format(metric));row.cells.put("Previous total",once?"":"0");
                } else {
                    boolean processed=current?!followup.contains(row.id)&&!row.get("Pre-report Views / attendance").isBlank():row.get("Pre-report Metric basis").equals("Trailing delta")&&!row.get("Pre-report Current total").isBlank()&&(!original.get("Month-end basis").equals("Selected monthly workbook snapshot")||!row.get("Measured at").isBlank());
                    String agentTotal=processed?row.get(current?"Pre-report Views / attendance":"Pre-report Current total"):"";
                    String total=override!=null&&override.asOf().equals(cutoff)?override.value():!agentTotal.isBlank()?agentTotal:snapshot==null?"":snapshot.get("Current total");
                    String previous=current?"0":snapshot==null?"":snapshot.get("Previous total");
                    if(current&&snapshot!=null&&!snapshot.get("Previous total").isBlank())previous=snapshot.get("Previous total");
                    if(current&&!row.get("Pre-report Previous total").isBlank())previous=row.get("Pre-report Previous total");
                    if(!current&&(processed||snapshot==null||override!=null))previous=row.get("Pre-report Previous total");
                    if(current&&matches.size()>1&&row.get("Pre-report Previous total").isBlank())throw new IllegalArgumentException("Ambiguous previous baseline in monthly workbook; correct the source rows");
                    BigDecimal baseline;
                    try {baseline=nonnegative(previous);}catch(IllegalArgumentException invalid){throw new IllegalArgumentException("Previous baseline is missing or invalid; correct it in phase 4 or the source workbook");}
                    if(total.isBlank())throw new IllegalArgumentException(matches.size()>1?"Ambiguous monthly snapshot match; enter an attributed total":"Reporting total unavailable; collect or confirm a total for the selected month");
                    metric=nonnegative(total).subtract(baseline);
                    row.cells.put("Current total",Calculations.format(nonnegative(total)));row.cells.put("Previous total",Calculations.format(nonnegative(previous)));
                    row.cells.put("Month-end basis",override!=null?"User-confirmed reporting total":!agentTotal.isBlank()?"Approved agent total assigned to selected reporting month":"Selected monthly workbook snapshot");
                    if(metric.signum()<0)throw new IllegalArgumentException("Negative month-end delta requires correction");
                }
            }catch(IllegalArgumentException invalid){reason=invalid.getMessage();}
            if(reason.isBlank()) {
                row.cells.put("Exact reporting metric",Calculations.format(metric));row.cells.put(VIEWS,Calculations.format(metric));
                row.cells.put("Impact hours",Calculations.format(metric.multiply(Calculations.number(rule.weight()))));
                row.cells.put("Reporting status","Included");
                if(!current) {row.cells.put("Metric basis","Trailing delta");row.cells.put("Growth classification","growth");row.cells.put("Exact trailing growth",Calculations.format(metric));row.cells.put("Impact basis","Exact month-end delta × DA metric model 2026 Weight");}
            } else {
                row.cells.put("Reporting reason",reason);
                if(current) {row.cells.put(VIEWS,"");row.cells.put("Current total","");}
                if(!current){row.cells.put("Growth classification",original.get("Metric basis").contains("not a trailing delta")?"retained":"unresolved");row.cells.put("Exact trailing growth","");}
            }
            if(once) {row.cells.put("Current total","");row.cells.put("Previous total","");}
            if(override!=null&&override.asOf().equals(cutoff)){row.cells.put("Month-end note",override.note());row.cells.put("Recorded at",override.recordedAt());}
            if(!reason.isBlank()&&(reason.equals("Unresolved follow-up activity")||reason.startsWith("Reporting total unavailable")||reason.startsWith("Ambiguous monthly snapshot")||reason.startsWith("Negative month-end delta")))row.cells.put("Reporting action","Confirm total");
            row.cells.put(NOTE,"Month-end reporting: "+(reason.isBlank()?row.get("Month-end basis"):reason));
        }
        return Data.output(data,rows);
    }
    public Report build(Run run,Data current,Data trailing,Data follow,String scope) throws Exception {
        if(!Set.of("current","trailing","combined").contains(scope))throw new BadRequestException("Unknown reporting scope");
        Report report=build(run,current,trailing,follow,scope,context(run));
        if(report.month().isBlank())return report;
        ImpactTrends.Snapshot history=null;Map<String,Object> source=new LinkedHashMap<>();
        Object metadata=run.workbook.get("chartHistory");if(metadata instanceof Map<?,?> map)map.forEach((key,value)->source.put(String.valueOf(key),value));
        Path historyFile=workspace.runPath(run.id,"rawdata/"+String.valueOf(source.getOrDefault("snapshotFilename","chart-history.xlsx")));
        if(Files.isRegularFile(historyFile))history=trends.read(Files.readAllBytes(historyFile),String.valueOf(source.getOrDefault("filename","")),String.valueOf(source.getOrDefault("endMonth","")));
        return new Report(report.month(),report.cutoff(),report.scope(),report.revision(),report.complete(),report.included(),report.excluded(),report.impact(),report.activity(),report.tool(),report.bob(),report.details(),report.warnings(),ImpactTrends.build(report.month(),report.activity(),report.tool(),history,Map.copyOf(source),report.included()>0||report.excluded()==0));
    }
    static Report build(Run run,Data current,Data trailing,Data follow,String scope,Context context) {
        Set<String> unresolved=new HashSet<>();follow.rows.forEach(row->unresolved.add(row.id));
        Data now=transform(run,current,true,context,unresolved),past=transform(run,trailing,false,context,Set.of());
        Set<List<String>> currentKeys=new HashSet<>();now.rows.forEach(row->currentKeys.add(TrailingReporting.activityKey(row)));
        List<Row> selected=new ArrayList<>();if(!scope.equals("trailing"))selected.addAll(now.rows);
        if(!scope.equals("current"))for(Row original:past.rows) {
            Row row=original.copy();
            if(scope.equals("combined")&&currentKeys.contains(TrailingReporting.activityKey(row))) {row.cells.put("Reporting status","Excluded");row.cells.put("Reporting reason","Also in current.csv; current contribution takes precedence");row.cells.put("Reporting action","");}
            selected.add(row);
        }
        Set<List<String>> seen=new HashSet<>();for(Row row:selected)if(row.get("Reporting status").equals("Included")&&!seen.add(TrailingReporting.activityKey(row))) {
            row.cells.put("Reporting status","Excluded");row.cells.put("Reporting reason","Duplicate activity attribution; first eligible contribution retained");row.cells.put("Reporting action","");
        }
        Data details=Data.output(new Data(FINAL_HEADERS,List.of()),selected);List<Row> eligible=selected.stream().filter(row->row.get("Reporting status").equals("Included")).toList();
        String month=String.valueOf(run.workbook.getOrDefault("month",""));List<String> warnings=new ArrayList<>();
        if(context.rules().isEmpty())warnings.add("DA metric model 2026 is missing. No weights are guessed.");
        if(!context.sameMonth())warnings.add("The historical workbook has no snapshot for the selected month. Approved agent totals are used; historical workbook values remain baselines.");
        if(selected.size()>eligible.size())warnings.add((selected.size()-eligible.size())+" rows are excluded. Review the detail reasons before treating this report as complete.");
        warnings.add("Approved agent counts are assigned to "+month+" as its final reporting data, including counts collected later. Measured at preserves the actual collection date; As of is the reporting month-end.");
        BigDecimal total=eligible.stream().map(row->Calculations.number(row.get("Impact hours"))).reduce(BigDecimal.ZERO,BigDecimal::add);
        var activity=aggregate(eligible,"Type of activity");var tool=aggregate(eligible,"Tool");
        return new Report(month,month.isBlank()?"":YearMonth.parse(month).atEndOfMonth().toString(),scope,run.reportingRevision,run.status.equals("COMPLETED"),eligible.size(),selected.size()-eligible.size(),Calculations.format(total),activity,tool,aggregate(eligible.stream().filter(row->row.get("Tool").equals("IBM Bob")).toList(),"Type of activity"),details,warnings,month.isBlank()?null:ImpactTrends.build(month,activity,tool,null,Map.of(),!eligible.isEmpty()||selected.isEmpty()));
    }
    static List<Aggregate> aggregate(List<Row> rows,String field) {
        Map<String,List<Row>> groups=new TreeMap<>();for(Row row:rows)groups.computeIfAbsent(row.get(field),k->new ArrayList<>()).add(row);
        List<Aggregate> result=new ArrayList<>();
        for(var group:groups.entrySet()) {
            BigDecimal current=BigDecimal.ZERO,trailing=BigDecimal.ZERO,metrics=BigDecimal.ZERO;Set<String> units=new TreeSet<>();
            for(Row row:group.getValue()){BigDecimal n=Calculations.number(row.get("Impact hours"));if(row.get("Contribution").equals("Current"))current=current.add(n);else trailing=trailing.add(n);metrics=metrics.add(Calculations.number(row.get("Exact reporting metric")));units.add(row.get("Metric unit"));}
            result.add(new Aggregate(group.getKey(),Calculations.format(current),Calculations.format(trailing),Calculations.format(current.add(trailing)),Calculations.format(metrics),String.join(" / ",units),group.getValue().size()));
        }
        result.sort(Comparator.comparing((Aggregate a)->Calculations.number(a.impact())).reversed().thenComparing(Aggregate::name));return result;
    }
}
