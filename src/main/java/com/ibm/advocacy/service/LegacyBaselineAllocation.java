package com.ibm.advocacy.service;

import com.ibm.advocacy.model.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;
import static com.ibm.advocacy.model.MetricsSchema.*;

/** Normalize only formula-proven, repeated full-resource totals across distinct author/tool shares. */
final class LegacyBaselineAllocation {
    private LegacyBaselineAllocation() {}
    static int divisor(Row row){
        String formula=row.get("Legacy views formula").replaceAll("\\s+","");
        Matcher match=Pattern.compile("^\\(([^()]*)\\)/([1-9][0-9]*)$").matcher(formula);
        if(!match.matches())return 0;
        String expression=match.group(1).toLowerCase(Locale.ROOT);
        if(!expression.contains("currenttotal")||!expression.contains("previoustotal")||expression.chars().filter(c->c=='-').count()!=1)return 0;
        try{return Integer.parseInt(match.group(2));}catch(NumberFormatException invalid){return 0;}
    }
    static List<String> resourceGroup(Row row){List<String> key=new ArrayList<>(TrailingReporting.activityKey(row));key.set(1,"");key.set(2,"");return key;}
    static Data normalize(Data source){
        List<Row> rows=source.rows.stream().map(Row::copy).toList();Map<List<String>,List<Row>> groups=new LinkedHashMap<>();
        for(Row row:rows)if(divisor(row)>1&&!row.get(URL).isBlank()&&row.get("Source run").isBlank())groups.computeIfAbsent(resourceGroup(row),k->new ArrayList<>()).add(row);
        for(List<Row> group:groups.values()){
            int n=divisor(group.getFirst());boolean valid=group.size()==n&&group.stream().map(TrailingReporting::activityKey).distinct().count()==n;
            try{
                BigDecimal current=Calculations.number(group.getFirst().get("Current total")),previous=Calculations.number(group.getFirst().get("Previous total"));
                BigDecimal expected=current.subtract(previous).divide(BigDecimal.valueOf(n),12,java.math.RoundingMode.HALF_UP);
                for(Row row:group)if(divisor(row)!=n||Calculations.number(row.get("Current total")).compareTo(current)!=0||Calculations.number(row.get("Previous total")).compareTo(previous)!=0||Calculations.number(row.get(VIEWS)).subtract(expected).abs().compareTo(new BigDecimal("0.0001"))>0)valid=false;
                if(valid){List<BigDecimal> totals=Calculations.allocate(current,n),baselines=Calculations.allocate(previous,n);for(int i=0;i<n;i++){Row row=group.get(i);row.cells.put("Baseline source current total",row.get("Current total"));row.cells.put("Baseline source previous total",row.get("Previous total"));row.cells.put("Current total",Calculations.format(totals.get(i)));row.cells.put("Previous total",Calculations.format(baselines.get(i)));row.cells.put("Baseline allocation","Formula-proven full resource totals divided across "+n+" author/tool shares");}}
            }catch(IllegalArgumentException invalid){valid=false;}
            if(!valid)for(Row row:group)row.cells.put("Baseline allocation issue","The divided formula and resource attribution group disagree; verify the original baseline shares");
        }
        return Data.output(source,rows);
    }
}
