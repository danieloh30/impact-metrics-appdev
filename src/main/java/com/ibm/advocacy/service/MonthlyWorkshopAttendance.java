package com.ibm.advocacy.service;

import com.ibm.advocacy.model.*;
import java.util.*;
import static com.ibm.advocacy.model.MetricsSchema.*;

/** Confirmed monthly rollups already contain the completed metric, not attendee headcounts. */
public final class MonthlyWorkshopAttendance {
    private MonthlyWorkshopAttendance() {}
    static final Set<String> ACTIVITIES=Set.of("customer workshops - monthly rh sales user experiences","customer workshops - monthly ibm sales user experiences");
    public static boolean matches(Row row){return row.type().equals("customer workshop")&&ACTIVITIES.contains(row.get("Name of activity").strip().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT));}
    static void stamp(Row row){row.cells.put("Attendance calculation","Supplied monthly total; duration not reapplied");row.cells.put("Attendance source total",row.get(VIEWS));row.cells.put("Attendance source duration",row.get("Duration (in hours)"));}
    /** Repair saved reports on copies from immutable source rows; never divide a stored share again. */
    static Data reconcile(Run run,Data artifact,Data source){
        Map<String,Row> shares=new HashMap<>();Map<String,String> errors=new HashMap<>();
        for(Row original:source.rows)if(matches(original)){
            Row input=original.copy();Measurement count=run.measurements.get("attendance:"+original.id);if(count!=null)input.cells.put(VIEWS,count.value());
            try{input.cells.put("Attendance hours",Calculations.format(Calculations.attendance(input)));stamp(input);for(Row share:Calculations.split(input,"Attendance hours"))shares.put(share.id,share);}
            catch(IllegalArgumentException error){errors.put(original.id,error.getMessage());}
        }
        List<Row> rows=new ArrayList<>();for(Row original:artifact.rows){Row row=original.copy();rows.add(row);if(!matches(row))continue;Row share=shares.get(row.id);
            if(share==null){row.cells.put("Attendance correction",errors.getOrDefault(ParticipationAttendance.sourceId(row),"Original monthly total is unavailable; re-import the source workbook"));continue;}
            row.cells.remove("Attendance correction");row.cells.putIfAbsent("Prior calculated attendance",row.get(VIEWS));
            for(String field:List.of(VIEWS,"Attendance hours"))row.cells.put(field,share.get(field));
            row.cells.put("Pre-report Views / attendance",share.get(VIEWS));row.cells.put("Current total",share.get(VIEWS));row.cells.put("Pre-report Current total",share.get(VIEWS));row.cells.put("Previous total","0");row.cells.put("Pre-report Previous total","0");
            share.cells.forEach((field,value)->{if(field.startsWith("Attendance "))row.cells.put(field,value);});
            row.cells.put(NOTE,"Supplied monthly total "+share.get("Attendance source total")+" allocated once across authors × tools; duration not reapplied; exact row metric "+share.get(VIEWS));
        }
        return Data.output(artifact,rows);
    }
}
