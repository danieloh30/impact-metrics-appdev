package com.ibm.advocacy.service;

import com.ibm.advocacy.model.Measurement;
import com.ibm.advocacy.model.Row;
import com.ibm.advocacy.model.Data;

import java.math.*;
import java.net.*;
import java.util.*;
import static com.ibm.advocacy.model.MetricsSchema.*;

public final class Calculations {
    private Calculations() {}
    public static BigDecimal number(String value) {
        if(value==null||value.isBlank())throw new IllegalArgumentException("Metric is blank");
        try { return new BigDecimal(value.replace(",","").strip()); }
        catch(NumberFormatException e) { throw new IllegalArgumentException("Invalid number: "+value); }
    }
    public static String format(BigDecimal n) { return n.stripTrailingZeros().toPlainString(); }
    /** Apply reporting precision to copies after exact attribution/delta calculations. */
    public static Data reported(Data source,boolean currentExport) {
        List<Row> rows=new ArrayList<>();
        for(Row original:source.rows) {
            Row row=original.copy();
            if(row.get(NOTE).equals("Only public HTTPS resource URLs are supported"))row.cells.put(NOTE,"Automatic collection is unavailable; open the resource and confirm or update its total.");
            try {
                row.cells.put(VIEWS,format(number(row.get(VIEWS)).setScale(0,RoundingMode.CEILING)));
                if(currentExport)row.cells.put("Current total",row.get(VIEWS));
            }catch(IllegalArgumentException invalid) { /* Preserve missing/invalid data for review. */ }
            rows.add(row);
        }
        return new Data(source.headers,rows);
    }
    public static BigDecimal attendance(Row row) {
        BigDecimal views=number(row.get(VIEWS));if(views.signum()<0)throw new IllegalArgumentException("Attendance cannot be negative");
        BigDecimal duration=row.get("Duration (in hours)").isBlank()?BigDecimal.ONE:number(row.get("Duration (in hours)"));
        return views.multiply(duration.max(BigDecimal.ONE));
    }
    public static List<BigDecimal> allocate(BigDecimal total,int count) {
        if(count<1)throw new IllegalArgumentException("Empty allocation");
        BigDecimal part=total.divide(BigDecimal.valueOf(count),6,RoundingMode.DOWN);
        List<BigDecimal> result=new ArrayList<>(Collections.nCopies(count,part));
        result.set(count-1,total.subtract(part.multiply(BigDecimal.valueOf(count-1))));return result;
    }
    static List<String> components(String value) {
        List<String> values=Arrays.stream(value.split("[;\r\n]+")).map(String::strip).filter(s->!s.isEmpty()).toList();
        return values.isEmpty()?List.of(""):values;
    }
    public static List<Row> split(Row original,String metric) {
        String author=original.cells.containsKey("Author of activity")?"Author of activity":"Author";
        List<String> authors=components(original.get(author)),tools=components(original.get("Tool"));
        List<BigDecimal> allocations=metric==null?null:allocate(number(original.get(metric)),authors.size()*tools.size());
        List<Row> result=new ArrayList<>();int index=0;
        for(String a:authors)for(String t:tools) {
            Row row=original.copy();row.id=original.id+"/"+index;row.cells.put(author,a);row.cells.put("Tool",t);
            if(allocations!=null) { String value=format(allocations.get(index));row.cells.put(VIEWS,value);if(metric.equals("Attendance hours"))row.cells.put(metric,value); }
            result.add(row);index++;
        }
        return result;
    }
    public static String urlKey(String value) {
        if(value.isBlank())return "";
        try { URI u=URI.create(value.strip());if(u.getHost()==null)return value.strip();return new URI(u.getScheme().toLowerCase(Locale.ROOT),null,u.getHost().toLowerCase(Locale.ROOT),u.getPort(),u.getPath(),u.getQuery(),null).toString(); }
        catch(Exception e) { return value.strip(); }
    }
    public static List<Row> trailing(List<Row> originals,Map<String,Measurement> measured) {
        Map<String,List<Row>> groups=new LinkedHashMap<>();List<Row> result=new ArrayList<>();
        for(Row original:originals) {
            Row row=original.copy();row.cells.put("Original Views / attendance",original.get(VIEWS));row.cells.put("Original Duration (in hours)",original.get("Duration (in hours)"));row.cells.put("Metric basis","Unresolved trailing delta");row.cells.put("Previous total",original.get("Current total"));row.cells.put(VIEWS,"");result.add(row);
            String key=urlKey(original.get(URL));if(key.isBlank())key="blank:"+original.id;
            groups.computeIfAbsent(key,k->new ArrayList<>()).add(row);
        }
        for(var group:groups.entrySet()) {
            Measurement m=measured.get(group.getKey());
            if(m==null) { group.getValue().forEach(r->r.cells.put(NOTE,"Current measurement unavailable — prior total retained; delta not calculated"));continue; }
            List<BigDecimal> allocations=allocate(number(m.value()),group.getValue().size());
            for(int i=0;i<allocations.size();i++) {
                Row row=group.getValue().get(i);row.cells.put("Metric basis","Trailing delta");row.cells.put("Current total",format(allocations.get(i)));
                try { BigDecimal delta=allocations.get(i).subtract(number(row.get("Previous total")));row.cells.put(VIEWS,format(delta));row.cells.put(NOTE,(delta.signum()<0?"Negative delta — review; ":"")+m.type()+" collected "+m.collectedAt()+(m.evidence()==null||m.evidence().isBlank()?"":"; "+m.evidence())); }
                catch(IllegalArgumentException e) { row.cells.put(NOTE,"Invalid previous baseline — "+e.getMessage()); }
            }
        }
        return result;
    }
    public static Row finalRow(Row source) {
        Row row=new Row(source.id,Map.of());
        for(String h:FINAL_HEADERS) {
            List<String> keys=switch(h) { case "Date"->List.of("Date of activity","Date");case "Author"->List.of("Author of activity","Author","Name");case "Event"->List.of("Name of event","Event");default->List.of(h); };
            row.cells.put(h,keys.stream().map(source::get).filter(s->!s.isEmpty()).findFirst().orElse(""));
        }
        return row;
    }
}
