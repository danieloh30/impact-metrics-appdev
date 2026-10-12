package com.ibm.advocacy.service;

import com.ibm.advocacy.model.*;
import java.util.*;
import static com.ibm.advocacy.model.MetricsSchema.*;

/** Recalculate older unsplit follow-ups from immutable source attendance, never from rounded shares. */
public final class ParticipationAttendance {
    private ParticipationAttendance() {}
    static boolean matches(Row row){return row.type().equals("standard participation");}
    static String sourceId(Row row){return row.id.contains("/")?row.id.substring(0,row.id.lastIndexOf('/')):row.id;}
    static void stamp(Row row) {
        row.cells.put("Participation calculation","Shared participation units");row.cells.put("Participation unit total","1");
        row.cells.put("Participation source attendance",row.get(VIEWS));
        row.cells.put("Participation source duration",row.get("Duration (in hours)").isBlank()?"1":row.get("Duration (in hours)"));
        row.cells.put("Participation authors",String.valueOf(Calculations.components(row.get("Author of activity").isBlank()?row.get("Author"):row.get("Author of activity")).size()));
        row.cells.put("Participation tools",String.valueOf(Calculations.components(row.get("Tool")).size()));
    }
    static Data reconcile(Run run,Data artifact,Data source) {
        Map<String,Row> shares=new HashMap<>();Map<String,String> errors=new HashMap<>();
        for(Row original:source.rows)if(matches(original)) {
            Row input=original.copy();Measurement count=run.measurements.get("attendance:"+original.id),duration=run.measurements.get("duration:"+original.id);
            if(count!=null)input.cells.put(VIEWS,count.value());if(duration!=null)input.cells.put("Duration (in hours)",duration.value());
            try {
                // One participation unit per activity; attendance and duration are source evidence, not multipliers.
                input.cells.put("Attendance hours",Calculations.format(Calculations.attendance(input)));stamp(input);
                for(Row share:Calculations.split(input,"Attendance hours"))shares.put(share.id,share);
            }catch(IllegalArgumentException invalid){errors.put(original.id,invalid.getMessage());}
        }
        List<Row> rows=new ArrayList<>();for(Row original:artifact.rows) {
            Row row=original.copy();rows.add(row);if(!matches(row))continue;
            Row share=shares.get(row.id);
            if(share==null){row.cells.put("Participation correction",errors.getOrDefault(sourceId(row),"Original participation activity is unavailable; re-import the source workbook"));continue;}
            row.cells.remove("Participation correction");
            row.cells.put(VIEWS,share.get(VIEWS));row.cells.put("Attendance hours",share.get("Attendance hours"));
            row.cells.put("Pre-report Views / attendance",share.get(VIEWS));row.cells.put("Current total",share.get(VIEWS));row.cells.put("Pre-report Current total",share.get(VIEWS));row.cells.put("Previous total","0");row.cells.put("Pre-report Previous total","0");
            for(var entry:share.cells.entrySet())if(entry.getKey().startsWith("Participation ")||Set.of("Source sheet","Source workbook").contains(entry.getKey()))row.cells.put(entry.getKey(),entry.getValue());
            String note="One participation unit ÷ ("+share.get("Participation authors")+" authors × "+share.get("Participation tools")+" tools); exact allocated units "+share.get(VIEWS)+". Source attendance/duration retained as evidence only.";
            row.cells.put(NOTE,note);row.cells.put("Live collection note",note);
        }
        return Data.output(artifact,rows);
    }
}
