package com.ibm.advocacy.service;

import com.ibm.advocacy.agentic.InstructionReviewer;
import com.ibm.advocacy.agentic.QualityReviewer;
import com.ibm.advocacy.collector.MetricCollectors;
import com.ibm.advocacy.storage.ResultStore;
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
    @Inject ResultStore results;
    @Inject MetricCollectors collectors;
    @Inject com.ibm.advocacy.collector.ManualCheckExclusions manualCheckExclusions;
    @Inject RunService runs;
    @Inject MonthlyReporting reporting;
    @Inject InstructionReviewer instructionReviewer;
    @Inject QualityReviewer qualityReviewer;
    @ConfigProperty(name="metrics.ai.enabled") boolean ai;
    public static final String CONTRACT="Workbook cell data directly to agents; structured results throughout phases; CSV only on download; supplied views+duration resolve blank current URLs; reported views use ceiling after exact allocation; valid no-URL historical views are retained without manual review or a delta; trailing eligibility honors Yes/No, blank flags default to digital only; preserve numeric baseline cells; configured blog/digital manual-check exclusions use workbook views without URL collection and no baseline is required when no delta is calculated; exact host/path/video rules; historical input is not a fresh delta; seven RUN phases in order; approve 1,2,3,4; human-reviewed direct SharePoint current-input publication after Phase 3 before trailing; upsert without deleting rows; checkout/hash/verification/check-in; frozen original baselines; separate same-month starting baseline; provisional versus completed monthly snapshots; final completion status and repeatable new runs without history reset; unresolved digital exclusively follow-up; reactions only; DZone background public article Views with phase-scoped browser reuse, explicit progress and approximate evidence for K/M/B display; GitHub stars; attendance * max(duration,1); Standard participation always shared across authors, automatic one-time Phase 2 attendance with no collection/month-end confirmation; authors*tools divisor; existing unsplit participation recalculated from original source; six-decimal conserved allocation; trailing old Current to Previous, new total / URL row count, delta; legacy 24-column export; SharePoint month-end reporting uses DA metric model 2026 weights, units and geography; Markus/Elder supplements one-time No with blank comparison totals; exact impact before view ceiling; approved agent totals including later collections assigned to selected reporting month; preserve actual collection dates; no repeat confirmation for resolved data; three SUM pivots with IBM Bob tool filter; published AppDev Advocacy history persisted outside Dev Services and frozen per run; third initial SharePoint source with cached reuse and independent refresh; stacked area charts by channel and tool from September 2025 through selected month; optional Bob chart; independent chart retry and source provenance; combined overlap suppression only for eligible current contributions; informational already-counted/no-growth omissions with zero counted impact; unresolved reporting exclusions; no follow-up recalculation; runtimes only.";
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
    Measurement measurement(Run run,Row row,CollectionTracker progress) throws Exception {
        String key=Calculations.urlKey(row.get(URL));if(key.isBlank())key="blank:"+row.id;
        if(run.measurements.containsKey(key)) {Measurement cached=run.measurements.get(key);progress.success(row,cached,true);return cached;}
        if(key.startsWith("blank:"))throw new IllegalArgumentException("No URL — manual collection required");
        progress.checking(row);
        try {
            Measurement measurement=collectors.collect(row.get(URL));
            run.measurements.put(key,measurement);progress.success(row,measurement,false);return measurement;
        } catch(Exception failure) {progress.failed(row,failure);throw failure;}
    }
    private static class InputMetricException extends IllegalArgumentException {
        final String metric;
        InputMetricException(String metric,String reason){super(reason);this.metric=metric;}
    }
    private String suppliedContentViews(Row row) {
        try {
            BigDecimal views=Calculations.number(row.get(VIEWS));
            if(views.signum()<0)throw new IllegalArgumentException("Views / attendance cannot be negative");
            return Calculations.format(views);
        } catch(IllegalArgumentException e) { throw new InputMetricException("VIEWS",message(e)); }
    }
    private Measurement digitalMeasurement(Run run,Row row,CollectionTracker progress) throws Exception {
        if(manualCheckExclusions.matches(row.get(URL))) {
            Measurement override=run.measurements.get(Calculations.urlKey(row.get(URL)));
            if(override!=null&&override.manual())return override;
            return new Measurement(suppliedContentViews(row),"INPUT_DATA",row.get(URL),run.createdAt,"Digital content metrics supplied by workbook; source excluded from manual collection",false);
        }

        if(row.get(URL).isBlank()&&!row.get(VIEWS).isBlank()&&!row.get("Duration (in hours)").isBlank()) {
            Measurement override=run.measurements.get("blank:"+row.id);
            String views=override==null?row.get(VIEWS):override.value();
            Measurement duration=run.measurements.get("duration:"+row.id);
            if(duration!=null)row.cells.put("Duration (in hours)",duration.value());
            try {if(Calculations.number(views).signum()<0)throw new IllegalArgumentException("Views / attendance cannot be negative");}
            catch(IllegalArgumentException e){throw new InputMetricException("VIEWS",message(e));}
            try {Calculations.number(row.get("Duration (in hours)"));}
            catch(IllegalArgumentException e){throw new InputMetricException("DURATION",message(e));}
            if(override!=null)return override;
            return new Measurement(Calculations.format(Calculations.number(views)),"INPUT_DATA","",run.createdAt,"Supplied Views / attendance and Duration (in hours); no URL required",false);
        }
        return measurement(run,row,progress);
    }
    public void digital(PhaseTask task) throws Exception {
        Run run=workspace.load(task.runId());run.issues.clear();Data source=workspace.data(run.id,"source");
        List<Row> digital=new ArrayList<>(),follow=new ArrayList<>();Set<String> failed=new HashSet<>();
        CollectionTracker progress=new CollectionTracker(workspace,run,source.rows.stream().filter(row->DIGITAL.contains(row.type())&&!row.get(URL).isBlank()&&!manualCheckExclusions.matches(row.get(URL))).toList(),collectors.backgroundDzone());
        try(AutoCloseable browserBatch=collectors.batch()) {
            for(Row original:source.rows)if(DIGITAL.contains(original.type())) {
                Row row=original.copy();
                try {
                    if(runs.cancelled(run.id))throw new java.util.concurrent.CancellationException("Run cancelled");
                    String key=Calculations.urlKey(row.get(URL));if(!manualCheckExclusions.matches(row.get(URL))&&!key.isBlank()&&failed.contains(key)&&!run.measurements.containsKey(key))throw new IllegalArgumentException("Collection failed for this shared URL; manual collection required");
                    Measurement m=digitalMeasurement(run,row,progress);row.cells.put(VIEWS,m.value());row.cells.put("Measured at",m.collectedAt());row.cells.put(NOTE,m.type()+" collected "+m.collectedAt()+" — "+m.evidence());
                    if(m.type().equals("REACTIONS")) { row.cells.put("total_reactions",m.value());row.cells.put("reactions_scraped_at",m.collectedAt()); }
                    digital.add(row);
                } catch(Exception e) {
                    if(e instanceof java.util.concurrent.CancellationException)throw e;
                    String key=Calculations.urlKey(row.get(URL));if(!key.isBlank())failed.add(key);row.cells.put(NOTE,message(e));follow.add(row);issue(run,row,message(e),e instanceof InputMetricException,e instanceof InputMetricException input?input.metric:MetricCollectors.type(row.get(URL)));
                }
            }
        } finally {progress.finish(runs.cancelled(run.id));}
        store(run,1,"digital.csv",Data.output(source,digital));store(run,1,"follow-up.csv",Data.output(source,follow));
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
                if(!row.get("Duration (in hours)").isBlank())try { if(ParticipationAttendance.matches(row)&&Calculations.number(row.get("Duration (in hours)")).signum()<0)throw new IllegalArgumentException("Duration cannot be negative");Calculations.number(row.get("Duration (in hours)")); }catch(IllegalArgumentException e) { row.cells.put(NOTE,message(e));follow.add(row);issue(run,row,message(e),true,"DURATION");continue; }
                row.cells.put("Attendance hours",Calculations.format(Calculations.attendance(row)));if(ParticipationAttendance.matches(row))ParticipationAttendance.stamp(row);person.add(row);
            } catch(IllegalArgumentException e) { row.cells.put(NOTE,message(e));follow.add(row);issue(run,row,message(e),true,"ATTENDANCE"); }
        }
        store(run,2,"digital.csv",stage(run,1,"digital.csv"));store(run,2,"in-person.csv",Data.output(source,person));store(run,2,"follow-up.csv",Data.output(source,follow));
        event(run,"AttendanceAgent","Calculated attendance for "+person.size()+" activities");workspace.save(run);
    }
    public void attribution(PhaseTask task) throws Exception {
        Run run=workspace.load(task.runId());run.issues.clear();
        for(String name:List.of("digital.csv","in-person.csv","follow-up.csv")) {
            Data input=stage(run,2,name);List<Row> rows=new ArrayList<>();String metric=name.equals("digital.csv")?VIEWS:name.equals("in-person.csv")?"Attendance hours":null;
            for(Row row:input.rows)rows.addAll(Calculations.split(row,metric));
            store(run,3,name,Data.output(input,rows));
        }
        event(run,"AttributionAgent","Split authors × tools using conserved decimal allocations");workspace.save(run);
    }
    public void trailing(PhaseTask task) throws Exception {
        Run run=workspace.load(task.runId());run.issues.clear();
        if(!run.trailing) { event(run,"Trailing90Agent","Skipped: no historical workbook/input rows selected");workspace.save(run);return; }
        Data originalSource=workspace.data(run.id,"trailing-source");
        Data source=new Data(originalSource.headers,originalSource.rows.stream().filter(TrailingEligibility::includes).map(original->{Row row=original.copy();row.cells.put("Current total",TrailingEligibility.baseline(row));return row;}).toList());
        run.trailingRows=source.rows.size();Set<String> attempted=new HashSet<>();
        CollectionTracker progress=new CollectionTracker(workspace,run,source.rows.stream().filter(row->!row.get(URL).isBlank()&&!run.inputReviews.containsKey(row.id)&&!TrailingEligibility.suppliedWithoutUrl(row)&&!manualCheckExclusions.matches(row.get(URL))).toList(),collectors.backgroundDzone());
        try(AutoCloseable browserBatch=collectors.batch()) {
            for(Row row:source.rows) {
                String key=Calculations.urlKey(row.get(URL));if(key.isBlank())key="blank:"+row.id;
                if(runs.cancelled(run.id))throw new java.util.concurrent.CancellationException("Run cancelled");
                if(run.inputReviews.containsKey(row.id)||TrailingEligibility.suppliedWithoutUrl(row))continue;
                if(manualCheckExclusions.matches(row.get(URL))) {
                    try { if(!run.measurements.containsKey(key)||!run.measurements.get(key).manual())suppliedContentViews(row); } catch(InputMetricException e) { issue(run,row,message(e),true,e.metric); }
                    continue;
                }
                if(attempted.add(key))try { measurement(run,row,progress); } catch(Exception e) { issue(run,row,message(e),false,MetricCollectors.type(row.get(URL))); }
            }
        } finally {progress.finish(runs.cancelled(run.id));}
        List<Row> baselines=new ArrayList<>();for(Row original:source.rows) {
            Row row=original.copy();Measurement baseline=run.measurements.get("baseline:"+row.id);if(baseline!=null)row.cells.put("Current total",baseline.value());
            boolean retained=run.inputReviews.containsKey(row.id)||manualCheckExclusions.matches(row.get(URL))||TrailingEligibility.suppliedWithoutUrl(row);
            if(!retained)try { if(Calculations.number(row.get("Current total")).signum()<0)throw new IllegalArgumentException("Previous baseline cannot be negative"); }
            catch(IllegalArgumentException e) { issue(run,row,row.get("Current total").isBlank()?"Previous baseline is missing in the historical metrics workbook":"Invalid previous baseline — "+e.getMessage(),true,"BASELINE"); }
            baselines.add(row);
        }
        if(run.workbook.containsKey("missingBaselines"))run.workbook.put("missingBaselines",run.issues.stream().filter(issue->issue.blocking()&&issue.metricType().equals("BASELINE")).count());
        Map<String,Measurement> fresh=new LinkedHashMap<>(run.measurements);Map<String,String> contentOverrides=new HashMap<>();
        Map<String,List<Row>> excludedGroups=new LinkedHashMap<>();
        for(Row row:source.rows)if(manualCheckExclusions.matches(row.get(URL)))excludedGroups.computeIfAbsent(Calculations.urlKey(row.get(URL)),key->new ArrayList<>()).add(row);
        for(var group:excludedGroups.entrySet()) {
            Measurement override=fresh.remove(group.getKey());
            if(override!=null&&override.manual()) {
                List<java.math.BigDecimal> shares=Calculations.allocate(Calculations.number(override.value()),group.getValue().size());
                for(int i=0;i<shares.size();i++)contentOverrides.put(group.getValue().get(i).id,Calculations.format(shares.get(i)));
            }
        }
        for(Row row:source.rows)if(TrailingEligibility.suppliedWithoutUrl(row))fresh.remove("blank:"+row.id);
        List<Row> rows=Calculations.trailing(baselines,fresh);
        for(Row row:rows) {
            if(manualCheckExclusions.matches(row.get(URL))&&!run.inputReviews.containsKey(row.id)) {
                Row original=source.rows.stream().filter(r->r.id.equals(row.id)).findFirst().orElseThrow();
                try {
                    row.cells.put(VIEWS,contentOverrides.containsKey(row.id)?contentOverrides.get(row.id):suppliedContentViews(original));
                    row.cells.put("Metric basis","Workbook content input — not a trailing delta");
                    row.cells.put(NOTE,"Content source excluded from manual collection; workbook views retained; no fresh lifetime total or trailing delta calculated");
                } catch(InputMetricException e) { row.cells.put(NOTE,e.getMessage()); }
                continue;
            }
            var input=run.inputReviews.get(row.id);
            Row supplied=source.rows.stream().filter(r->r.id.equals(row.id)).findFirst().orElseThrow();
            if(input==null&&TrailingEligibility.suppliedWithoutUrl(supplied)) {
                Row original=source.rows.stream().filter(r->r.id.equals(row.id)).findFirst().orElseThrow();
                row.cells.put(VIEWS,original.get(VIEWS));row.cells.put("Current total",original.get("Current total"));row.cells.put("Previous total",original.get("Current total"));
                row.cells.put("Metric basis","Supplied historical input — not a trailing delta");
                row.cells.put(NOTE,"Historical workbook views retained; no URL collection, fresh lifetime total or trailing delta calculated");
                continue;
            }
            if(input==null)continue;

            Row original=source.rows.stream().filter(r->r.id.equals(row.id)).findFirst().orElseThrow();
            row.cells.put(VIEWS,input.views());row.cells.put("Duration (in hours)",input.duration());
            row.cells.put("Current total",original.get("Current total"));row.cells.put("Previous total",original.get("Current total"));
            row.cells.put("Metric basis",input.edited()?"Reviewed input — not a trailing delta":"Retained input — not a trailing delta");
            row.cells.put(NOTE,input.note()+"; no fresh lifetime total or trailing delta calculated");
        }
        for(Row row:rows)if(row.get(NOTE).startsWith("Invalid previous baseline")&&run.issues.stream().noneMatch(i->i.rowId().equals(row.id)&&i.metricType().equals("BASELINE")))issue(run,row,row.get(NOTE),true,"BASELINE");
        rows=rows.stream().map(TrailingReporting::normalize).toList();
        store(run,4,"updated_90-day.csv",reporting.artifact(run,Data.output(source,rows),false));
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
            store(run,5,"follow-up.csv",Data.output(source,rows));
        } else if(task.phase()==6) {
            List<Row> rows=new ArrayList<>();for(String name:List.of("digital.csv","in-person.csv","follow-up.csv"))for(Row row:stage(run,5,name).rows) {
                Row result=Calculations.finalRow(row);
                if("MONTH_END".equals(run.workbook.get("reportingPolicy")))for(String field:List.of("Source workbook","Source sheet","Measured at"))if(row.cells.containsKey(field))result.cells.put(field,row.get(field));
                if(ParticipationAttendance.matches(row))row.cells.forEach((field,value)->{if(field.startsWith("Participation ")||field.equals("Attendance hours"))result.cells.put(field,value);});
                rows.add(result);
            }
            store(run,6,"current.csv",new Data(FINAL_HEADERS,rows));
        } else if(task.phase()==7) {
            Data data=stage(run,6,"current.csv");for(Row row:data.rows)row.cells.put("Current total",row.get(VIEWS));store(run,7,"current.csv",reporting.artifact(run,data,true));
        }
        event(run,"ExportAgent",PHASES.get(task.phase()-1)+" complete");workspace.save(run);
    }
    public PhaseResult publish(PhaseTask task) throws Exception {
        Run run=workspace.load(task.runId());List<String> names=switch(task.phase()) { case 1->List.of("digital.csv","follow-up.csv");case 2,3,5->List.of("digital.csv","in-person.csv","follow-up.csv");case 4->run.trailing?List.of("updated_90-day.csv"):List.of();default->List.of("current.csv"); };
        int version=run.version+1;
        for(String name:names) {
            Data data=stage(run,task.phase(),name);Set<String> ids=new HashSet<>();
            for(Row row:data.rows)if(!ids.add(row.id))throw new IllegalStateException("Duplicate source attribution: "+row.id);
            run.artifacts.put(name,results.save(run.id,name,version,task.phase(),data));
        }
        if(task.phase()==7) {
            Data current=stage(run,7,"current.csv");
            boolean monthEnd="MONTH_END".equals(run.workbook.get("reportingPolicy"));
            boolean validSchema=monthEnd?current.headers.size()>=FINAL_HEADERS.size()
                    &&current.headers.subList(0,FINAL_HEADERS.size()).equals(FINAL_HEADERS)
                    &&current.headers.containsAll(List.of("Reporting month","Reporting status","Reporting reason","Exact reporting metric","As of"))
                    &&new HashSet<>(current.headers).size()==current.headers.size():current.headers.equals(FINAL_HEADERS);
            if(!validSchema)throw new IllegalStateException("Invalid final export schema");
            long represented=current.rows.stream().map(r->r.id.split("/")[0]).distinct().count();if(represented!=run.currentRows)throw new IllegalStateException("Source-row conservation failed");
        }
        run.version=version;String summary=PHASES.get(task.phase()-1)+": "+names.size()+" artifacts; "+run.issues.size()+" issues.";
        if(ai)try { run.review=qualityReviewer.review(summary+" "+run.issues.stream().map(Issue::reason).limit(10).toList()); }catch(Exception e) { run.review="AI review unavailable. "+summary; }
        else run.review=summary+" Review the phase results before continuing.";
        event(run,"ValidationAgent",summary);workspace.save(run);return new PhaseResult(run.id,task.phase(),summary);
    }
    Data stage(Run run,int phase,String name) throws Exception {
        String key="phase-"+phase+"-"+ResultStore.datasetName(name);
        // Older JSON phase snapshots included the export suffix in their filename.
        if(!Files.exists(workspace.runPath(run.id,"state/"+key+".json")))key="phase-"+phase+"-"+name;
        return workspace.data(run.id,key);
    }
    void store(Run run,int phase,String name,Data data) throws Exception { workspace.data(run.id,"phase-"+phase+"-"+ResultStore.datasetName(name),data); }
    static void issue(Run run,Row row,String reason,boolean blocking,String metric) { run.issues.add(new Issue(row.id,row.get("Name of activity"),row.get(URL),reason,blocking,metric)); }
    static void event(Run run,String agent,String message) { run.events.add(new Event(run.events.size()+1L,Instant.now().toString(),agent,message)); }
    static String message(Exception e) { Throwable root=e;while(root.getCause()!=null&&root.getCause()!=root)root=root.getCause();return Optional.ofNullable(root.getMessage()).orElse(root.getClass().getSimpleName()); }
}
