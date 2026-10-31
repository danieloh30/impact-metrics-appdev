package com.ibm.advocacy.service;

import com.ibm.advocacy.model.*;
import java.math.BigDecimal;
import java.util.*;
import static com.ibm.advocacy.model.MetricsSchema.*;

/** Booth duty uses supplied positive attendance and actual hours, allocated exactly once. */
public final class BoothAttendance {
    private BoothAttendance() {}
    static boolean matches(Row row){return row.type().equals("booth duty");}
    static BigDecimal total(Row row){
        BigDecimal attendance=Calculations.number(row.get(VIEWS)),hours=Calculations.number(row.get("Duration (in hours)"));
        if(attendance.signum()<=0||hours.signum()<=0)throw new IllegalArgumentException("Booth duty needs attendance and duration greater than zero");
        return attendance.multiply(hours);
    }
    static boolean eligible(Row row){try{total(row);return true;}catch(IllegalArgumentException invalid){return false;}}
    static void stamp(Row row){
        row.cells.put("Booth calculation","Attendance × actual duration allocated once across authors × tools");
        row.cells.put("Booth source attendance",row.get(VIEWS));row.cells.put("Booth source duration",row.get("Duration (in hours)"));
    }
    /** Reconcile completed reports using immutable source, never an already allocated/rounded value. */
    static Data reconcile(Run run,Data artifact,Data source){
        Map<String,Row> shares=new HashMap<>();
        for(Row original:source.rows)if(matches(original)&&eligible(original)){
            Row input=original.copy();input.cells.put("Attendance hours",Calculations.format(total(input)));stamp(input);
            for(Row share:Calculations.split(input,"Attendance hours"))shares.put(share.id,share);
        }
        List<Row> rows=new ArrayList<>();for(Row original:artifact.rows){
            Row row=original.copy();rows.add(row);if(!matches(row))continue;
            Row share=shares.get(row.id);if(share==null)continue;
            for(String field:List.of(VIEWS,"Attendance hours"))row.cells.put(field,share.get(field));
            row.cells.put("Pre-report Views / attendance",share.get(VIEWS));row.cells.put("Current total",share.get(VIEWS));row.cells.put("Pre-report Current total",share.get(VIEWS));row.cells.put("Previous total","0");row.cells.put("Pre-report Previous total","0");
            share.cells.forEach((field,value)->{if(field.startsWith("Booth "))row.cells.put(field,value);});
            String note="Booth duty: "+share.get("Booth source attendance")+" attendees × "+share.get("Booth source duration")+" hours, allocated once across authors × tools; exact row attendance hours "+share.get(VIEWS);
            row.cells.put(NOTE,note);row.cells.put("Live collection note",note);
        }
        return Data.output(artifact,rows);
    }
}
