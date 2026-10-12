package com.ibm.advocacy.service;

import com.ibm.advocacy.model.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import static com.ibm.advocacy.model.MetricsSchema.*;

/** Reporting interpretation on copies; immutable workbook/artifact snapshots stay intact. */
public final class TrailingReporting {
    private TrailingReporting() {}
    public static String category(Row row) {
        if(row.get("Metric basis").equals("Trailing delta")) {
            try { Calculations.number(row.get(VIEWS)); return "growth"; }
            catch(IllegalArgumentException invalid) { return "unresolved"; }
        }
        return row.get("Metric basis").contains("not a trailing delta")?"retained":"unresolved";
    }
    public static Row normalize(Row original) {
        Row row=original.copy();
        row.cells.putIfAbsent("Original Impact hours",row.get("Impact hours"));
        row.cells.put("Growth classification",category(original));
        row.cells.put("Exact trailing growth","");
        row.cells.put("Impact hours","");
        row.cells.put("Impact basis","Not calculated — no verified trailing delta");
        if(category(original).equals("growth")) {
            BigDecimal delta=Calculations.number(original.get(VIEWS));
            row.cells.put("Exact trailing growth",Calculations.format(delta));
            try {
                BigDecimal weight=Calculations.number(original.get("Weight"));
                if(weight.signum()<0)throw new IllegalArgumentException("Negative weight");
                row.cells.put("Impact hours",Calculations.format(delta.multiply(weight)));
                row.cells.put("Impact basis","Exact trailing delta × Weight (before view rounding)");
            } catch(IllegalArgumentException invalid) {
                row.cells.put("Impact basis","Not calculated — missing or invalid Weight");
            }
        }
        return row;
    }
    static String measuredAt(Row row) {
        if(!row.get("Measured at").isBlank())return row.get("Measured at");
        // Legacy snapshots recorded the timestamp in their own collection note.
        // Never replace an older artifact's time with a newer mutable cache value.
        var match=Pattern.compile("\\bcollected ([0-9T:Z.+-]+)").matcher(row.get("Live collection note").isBlank()?row.get(NOTE):row.get("Live collection note"));
        if(match.find())try {return Instant.parse(match.group(1)).toString();}
        catch(java.time.format.DateTimeParseException ignored) {}
        return "";
    }
    public static Data enrich(Data source,Data current) {
        Set<List<String>> keys=new HashSet<>();
        if(current!=null)current.rows.forEach(row->keys.add(activityKey(row)));
        List<Row> rows=new ArrayList<>();
        for(Row original:source.rows) {
            Row row=normalize(original);
            row.cells.put("Also in current.csv",current==null?"Not available yet":keys.contains(activityKey(row))?"Yes":"No");
            row.cells.put("Measured at",category(row).equals("growth")?measuredAt(row):"");
            rows.add(row);
        }
        return Data.output(source,rows);
    }
    static String identityText(String value) {
        return java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC).replace("\u0092","'").replace("\u2018","'").replace("\u2019","'").replace("\u0097","-").replace("\u2013","-").replace("\u2014","-").strip().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
    }
    static List<String> activityKey(Row row) {
        String url=Calculations.urlKey(row.get(URL)).replaceFirst("^http://","https://").replaceFirst("^(https://)www\\.","$1");
        String date=first(row,"Date","Date of activity");try{date=WorkbookService.parseDate(date,"identity",1).toString();}catch(IllegalArgumentException|jakarta.ws.rs.BadRequestException ignored){}
        String event=identityText(first(row,"Event","Name of event"));
        if(row.type().equals("blog post / slide shares / .io updates")&&!url.isBlank())try{if(event.equals(java.net.URI.create(url).getHost()))event="";}catch(IllegalArgumentException ignored){}
        return List.of(identityText(date),identityText(first(row,"Author","Author of activity")),identityText(row.get("Tool")),identityText(row.get("Name of activity")),event,identityText(row.get("Type of activity")),url,identityText(row.get("Activity stage")));
    }
    static List<String> collectionAttribution(Row row) {
        if(row.get("Name of activity").isBlank()||first(row,"Author","Author of activity").isBlank()||row.get("Tool").isBlank()||first(row,"Date","Date of activity").isBlank())return List.of(row.id);
        return activityKey(row);
    }
    private static String first(Row row,String a,String b) { return row.get(a).isBlank()?row.get(b):row.get(a); }
    public static Map<String,Object> summary(Data data,Run run) {
        int growth=0,retained=0,unresolved=0,overlap=0,missingWeight=0;
        BigDecimal views=BigDecimal.ZERO,impact=BigDecimal.ZERO;
        SortedSet<Instant> times=new TreeSet<>();
        for(Row row:data.rows) {
            switch(row.get("Growth classification")) {
                case "growth" -> { growth++; views=views.add(Calculations.number(row.get("Exact trailing growth")));
                    try { impact=impact.add(Calculations.number(row.get("Impact hours"))); }
                    catch(IllegalArgumentException invalid) {missingWeight++;}
                }
                case "retained" -> retained++;
                default -> unresolved++;
            }
            if(row.get("Also in current.csv").equals("Yes"))overlap++;
            try {times.add(Instant.parse(row.get("Measured at")));}catch(java.time.format.DateTimeParseException ignored) {}
        }
        Map<String,Object> summary=new LinkedHashMap<>();
        summary.put("growthRows",growth);summary.put("retainedRows",retained);summary.put("unresolvedRows",unresolved);
        summary.put("currentAvailable",run.artifacts.containsKey("current.csv"));summary.put("overlapRows",overlap);summary.put("missingWeightRows",missingWeight);
        summary.put("exactGrowth",Calculations.format(views));summary.put("impactHours",Calculations.format(impact));
        summary.put("firstMeasuredAt",times.isEmpty()?"":times.first().toString());summary.put("lastMeasuredAt",times.isEmpty()?"":times.last().toString());
        summary.put("snapshotImportedAt",run.createdAt);summary.put("baseline",run.workbook);
        return summary;
    }
}
