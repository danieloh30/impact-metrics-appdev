package com.ibm.advocacy.service;

import com.ibm.advocacy.agentic.InstructionReviewer;
import com.ibm.advocacy.agentic.QualityReviewer;
import com.ibm.advocacy.collector.MetricCollectors;
import com.ibm.advocacy.csv.CsvCodec;
import com.ibm.advocacy.model.Artifact;
import com.ibm.advocacy.model.Data;
import com.ibm.advocacy.model.Event;
import com.ibm.advocacy.model.Issue;
import com.ibm.advocacy.model.Measurement;
import com.ibm.advocacy.model.PhaseResult;
import com.ibm.advocacy.model.PhaseTask;
import com.ibm.advocacy.model.Row;
import com.ibm.advocacy.model.Run;
import com.ibm.advocacy.storage.RuntimeWorkspace;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.math.BigDecimal;
import static com.ibm.advocacy.model.MetricsSchema.*;

@ApplicationScoped
@io.quarkus.arc.Unremovable
public class Pipeline {
    @Inject RuntimeWorkspace workspace;
    @Inject CsvCodec csv;
    @Inject MetricCollectors collectors;
    @Inject RunService runs;
    @Inject InstructionReviewer instructionReviewer;
    @Inject QualityReviewer qualityReviewer;
    @ConfigProperty(name="metrics.ai.enabled") boolean ai;
    public static final String CONTRACT="Seven RUN phases in order; approve 1,2,3,4; unresolved digital exclusively follow-up; reactions only; GitHub stars; attendance * max(duration,1); authors*tools divisor; six-decimal conserved allocation; trailing old Current to Previous, new total / URL row count, delta; exact 24-column current export; Current total = Views / attendance; no follow-up recalculation; runtimes only.";
    public void instructions(PhaseTask task) throws Exception {
        String text=Files.readString(workspace.runPath(task.runId(),"instructions/RUN.md"))+"\n"+Files.readString(workspace.runPath(task.runId(),"instructions/AGENTS.md"));
        Run run=workspace.load(task.runId());
        if(!RuntimeWorkspace.hash(text.getBytes(StandardCharsets.UTF_8)).equals(run.instructionHash))throw new IllegalStateException("Frozen instructions changed; create a new run");
        if(task.phase()==1&&ai) {
            try { run.review=instructionReviewer.review(text,CONTRACT); }
            catch(Exception e) { run.review="AI instruction review unavailable; deterministic contract remains active. "+e.getClass().getSimpleName(); }
        }
        event(run,"InstructionAgent","Checked frozen RUN.md and AGENTS.md");workspace.save(run);
    }
    Measurement measurement(Run run,Row row) throws Exception {
        String key=Calculations.urlKey(row.get(URL));if(key.isBlank())key="blank:"+row.id;
        if(run.measurements.containsKey(key))return run.measurements.get(key);
        if(key.startsWith("blank:"))throw new IllegalArgumentException("No URL — manual collection required");
        Measurement measurement=collectors.collect(row.get(URL));
        run.measurements.put(key,measurement);workspace.save(run);return measurement;
    }
    public void digital(PhaseTask task) throws Exception {
        Run run=workspace.load(task.runId());run.issues.clear();Data source=workspace.data(run.id,"source");
        List<Row> digital=new ArrayList<>(),follow=new ArrayList<>();Set<String> failed=new HashSet<>();
        for(Row original:source.rows)if(DIGITAL.contains(original.type())) {
            Row row=original.copy();
            try {
                if(runs.cancelled(run.id))throw new java.util.concurrent.CancellationException("Run cancelled");
                String key=Calculations.urlKey(row.get(URL));if(!key.isBlank()&&failed.contains(key)&&!run.measurements.containsKey(key))throw new IllegalArgumentException("Collection failed for this shared URL; manual collection required");
                Measurement m=measurement(run,row);row.cells.put(VIEWS,m.value());row.cells.put(NOTE,m.type()+" collected "+m.collectedAt()+" — "+m.evidence());
                if(m.type().equals("REACTIONS")) { row.cells.put("total_reactions",m.value());row.cells.put("reactions_scraped_at",m.collectedAt()); }
                digital.add(row);
            } catch(Exception e) {
                if(e instanceof java.util.concurrent.CancellationException)throw e;
                String key=Calculations.urlKey(row.get(URL));if(!key.isBlank())failed.add(key);row.cells.put(NOTE,message(e));follow.add(row);issue(run,row,message(e),false,MetricCollectors.type(row.get(URL)));
            }
        }
        store(run,1,"digital.csv",csv.output(source,digital));store(run,1,"follow-up.csv",csv.output(source,follow));
        event(run,"DigitalMetricsAgent","Collected "+digital.size()+" digital activities; "+follow.size()+" require follow-up");workspace.save(run);
    }
    public void attendance(PhaseTask task) throws Exception {
        Run run=workspace.load(task.runId());run.issues.clear();Data source=workspace.data(run.id,"source");
        List<Row> person=new ArrayList<>(),follow=new ArrayList<>(stage(run,1,"follow-up.csv").rows);
        for(Row original:source.rows)if(PERSON.contains(original.type())) {
            Row row=original.copy();
            if(row.type().equals("booth duty")) { row.cells.put(NOTE,"Booth duty — awaiting Field Marketing lead count");follow.add(row);continue; }
            try {
                Measurement override=run.measurements.get("attendance:"+row.id);if(override!=null)row.cells.put(VIEWS,override.value());
                Measurement duration=run.measurements.get("duration:"+row.id);if(duration!=null)row.cells.put("Duration (in hours)",duration.value());
                // Validate each field separately so the SPA can correct the failing value.
                try { Calculations.number(row.get(VIEWS)); }catch(IllegalArgumentException e) { row.cells.put(NOTE,message(e));follow.add(row);issue(run,row,message(e),true,"ATTENDANCE");continue; }
                if(!row.get("Duration (in hours)").isBlank())try { Calculations.number(row.get("Duration (in hours)")); }catch(IllegalArgumentException e) { row.cells.put(NOTE,message(e));follow.add(row);issue(run,row,message(e),true,"DURATION");continue; }
                row.cells.put("Attendance hours",Calculations.format(Calculations.attendance(row)));person.add(row);
            } catch(IllegalArgumentException e) { row.cells.put(NOTE,message(e));follow.add(row);issue(run,row,message(e),true,"ATTENDANCE"); }
        }
        store(run,2,"digital.csv",stage(run,1,"digital.csv"));store(run,2,"in-person.csv",csv.output(source,person));store(run,2,"follow-up.csv",csv.output(source,follow));
        event(run,"AttendanceAgent","Calculated attendance for "+person.size()+" activities");workspace.save(run);
    }
    public void attribution(PhaseTask task) throws Exception {
        Run run=workspace.load(task.runId());run.issues.clear();
        for(String name:List.of("digital.csv","in-person.csv","follow-up.csv")) {
            Data input=stage(run,2,name);List<Row> rows=new ArrayList<>();String metric=name.equals("digital.csv")?VIEWS:name.equals("in-person.csv")?"Attendance hours":null;
            for(Row row:input.rows)rows.addAll(Calculations.split(row,metric));
            store(run,3,name,csv.output(input,rows));
        }
        event(run,"AttributionAgent","Split authors × tools using conserved decimal allocations");workspace.save(run);
    }
    public void trailing(PhaseTask task) throws Exception {
        Run run=workspace.load(task.runId());run.issues.clear();
        if(!run.trailing) { event(run,"Trailing90Agent","Skipped: no trailing_90-day.csv imported");workspace.save(run);return; }
        Data source=workspace.data(run.id,"trailing-source");Set<String> attempted=new HashSet<>();
        for(Row row:source.rows) {
            String key=Calculations.urlKey(row.get(URL));if(key.isBlank())key="blank:"+row.id;
            if(runs.cancelled(run.id))throw new java.util.concurrent.CancellationException("Run cancelled");
            if(attempted.add(key))try { measurement(run,row); } catch(Exception e) { issue(run,row,message(e),false,MetricCollectors.type(row.get(URL))); }
        }
        List<Row> baselines=new ArrayList<>();for(Row original:source.rows) { Row row=original.copy();Measurement baseline=run.measurements.get("baseline:"+row.id);if(baseline!=null)row.cells.put("Current total",baseline.value());baselines.add(row); }
        List<Row> rows=Calculations.trailing(baselines,run.measurements);
        for(Row row:rows)if(row.get(NOTE).startsWith("Invalid previous baseline"))issue(run,row,row.get(NOTE),true,"BASELINE");
        store(run,4,"updated_90-day.csv",csv.output(source,rows));
        event(run,"Trailing90Agent","Refreshed "+source.rows.size()+" rows using "+attempted.size()+" resource groups");workspace.save(run);
    }
    public void finish(PhaseTask task) throws Exception {
        Run run=workspace.load(task.runId());run.issues.clear();
        if(task.phase()==5) {
            Data source=workspace.data(run.id,"source");Data follow=stage(run,3,"follow-up.csv");List<Row> rows=new ArrayList<>(follow.rows);
            for(Row original:source.rows)if(!DIGITAL.contains(original.type())&&!PERSON.contains(original.type())) {
                Row row=original.copy();row.cells.put(NOTE,OTHER.contains(row.type())?"Other activity — manual follow-up":"Unknown activity type — manual follow-up");rows.addAll(Calculations.split(row,null));
            }
            for(String name:List.of("digital.csv","in-person.csv"))store(run,5,name,stage(run,3,name));
            store(run,5,"follow-up.csv",csv.output(source,rows));
        } else if(task.phase()==6) {
            List<Row> rows=new ArrayList<>();for(String name:List.of("digital.csv","in-person.csv","follow-up.csv"))for(Row row:stage(run,5,name).rows)rows.add(Calculations.finalRow(row));
            store(run,6,"current.csv",new Data(FINAL_HEADERS,rows));
        } else if(task.phase()==7) {
            Data data=stage(run,6,"current.csv");for(Row row:data.rows)row.cells.put("Current total",row.get(VIEWS));store(run,7,"current.csv",data);
        }
        event(run,"ExportAgent",PHASES.get(task.phase()-1)+" complete");workspace.save(run);
    }
    public PhaseResult publish(PhaseTask task) throws Exception {
        Run run=workspace.load(task.runId());List<String> names=switch(task.phase()) { case 1->List.of("digital.csv","follow-up.csv");case 2,3,5->List.of("digital.csv","in-person.csv","follow-up.csv");case 4->run.trailing?List.of("updated_90-day.csv"):List.of();default->List.of("current.csv"); };
        int version=run.version+1;
        for(String name:names) {
            Data data=stage(run,task.phase(),name);Set<String> ids=new HashSet<>();
            for(Row row:data.rows)if(!ids.add(row.id))throw new IllegalStateException("Duplicate source attribution: "+row.id);
            byte[] bytes=csv.write(data);workspace.bytes(workspace.runPath(run.id,"output/"+version+"/"+name),bytes);
            run.artifacts.put(name,new Artifact(name,version,task.phase(),data.rows.size(),RuntimeWorkspace.hash(bytes)));
        }
        if(task.phase()==7) {
            Data current=stage(run,7,"current.csv");if(!current.headers.equals(FINAL_HEADERS))throw new IllegalStateException("Invalid final export schema");
            long represented=current.rows.stream().map(r->r.id.split("/")[0]).distinct().count();if(represented!=run.currentRows)throw new IllegalStateException("Source-row conservation failed");
        }
        run.version=version;String summary=PHASES.get(task.phase()-1)+": "+names.size()+" artifacts; "+run.issues.size()+" issues.";
        if(ai)try { run.review=qualityReviewer.review(summary+" "+run.issues.stream().map(Issue::reason).limit(10).toList()); }catch(Exception e) { run.review="AI review unavailable. "+summary; }
        else run.review=summary+" Review the generated files before continuing.";
        event(run,"ValidationAgent",summary);workspace.save(run);return new PhaseResult(run.id,task.phase(),summary);
    }
    Data stage(Run run,int phase,String name) throws Exception { return workspace.data(run.id,"phase-"+phase+"-"+name); }
    void store(Run run,int phase,String name,Data data) throws Exception { workspace.data(run.id,"phase-"+phase+"-"+name,data); }
    static void issue(Run run,Row row,String reason,boolean blocking,String metric) { run.issues.add(new Issue(row.id,row.get("Name of activity"),row.get(URL),reason,blocking,metric)); }
    static void event(Run run,String agent,String message) { run.events.add(new Event(run.events.size()+1L,Instant.now().toString(),agent,message)); }
    static String message(Exception e) { Throwable root=e;while(root.getCause()!=null&&root.getCause()!=root)root=root.getCause();return Optional.ofNullable(root.getMessage()).orElse(root.getClass().getSimpleName()); }
}
