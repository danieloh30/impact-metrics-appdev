package com.ibm.advocacy.service;

import com.ibm.advocacy.agentic.PhaseWorkflow;
import com.ibm.advocacy.collector.MetricCollectors;
import com.ibm.advocacy.csv.CsvCodec;
import com.ibm.advocacy.model.Approval;
import com.ibm.advocacy.model.Artifact;
import com.ibm.advocacy.model.Data;
import com.ibm.advocacy.model.Issue;
import com.ibm.advocacy.model.Measurement;
import com.ibm.advocacy.model.PhaseTask;
import com.ibm.advocacy.model.Row;
import com.ibm.advocacy.model.Run;
import com.ibm.advocacy.storage.RuntimeWorkspace;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import static com.ibm.advocacy.model.MetricsSchema.*;

@ApplicationScoped
public class RunService {
    @Inject RuntimeWorkspace workspace;
    @Inject CsvCodec csv;
    @Inject MonthlyReporting reporting;
    @Inject ReportingWorkbook reportingWorkbook;
    @Inject ImpactTrends impactTrends;
    @Inject com.ibm.advocacy.storage.ChartHistoryStore chartHistoryStore;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="metrics.reporting.chart-history-url") String chartHistoryUrl;
    @Inject com.ibm.advocacy.collector.SharePointCollector chartCollector;
    @Inject com.ibm.advocacy.storage.ResultStore results;
    @Inject PhaseWorkflow workflow;
    @Inject com.ibm.advocacy.agentic.WorkbookWorkflow workbookWorkflow;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="metrics.instructions.sha256") String supportedInstructions;
    private final ExecutorService workers=Executors.newFixedThreadPool(3);
    private final ConcurrentMap<String,Object> locks=new ConcurrentHashMap<>();
    private final Set<String> active=ConcurrentHashMap.newKeySet(),cancelled=ConcurrentHashMap.newKeySet();
    Object lock(String id) { return locks.computeIfAbsent(id,k->new Object()); }
    @PreDestroy void close() { workers.shutdownNow(); }
    public synchronized Run createWorkbook(byte[] bytes,String filename,String month,String currentSheet,List<String> historySheets) throws Exception {
return createWorkbook(new com.ibm.advocacy.model.WorkbookRequest(bytes,month,currentSheet,historySheets),filename,null);
    }
    private Run createWorkbook(com.ibm.advocacy.model.WorkbookRequest request,String filename,String historyFilename) throws Exception {
byte[] bytes=request.bytes();String currentSheet=request.currentSheet();
com.ibm.advocacy.model.PreparedWorkbook prepared;
try { prepared=workbookWorkflow.execute(UUID.randomUUID().toString(),request); }
catch(RuntimeException failure) {
    // Agent reflection wrappers must preserve actionable 4xx validation errors.
    for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof jakarta.ws.rs.WebApplicationException web)throw web;
    throw failure;
}
        for(Data data:new Data[]{prepared.current(),prepared.trailing()})if(data!=null) {
            if(!data.headers.contains("Source workbook"))data.headers.add("Source workbook");
            String source=data==prepared.current()?filename:(historyFilename==null?filename:historyFilename);
            data.rows.forEach(row->row.cells.put("Source workbook",source));
        }
        Run run=createPrepared(prepared.current(),prepared.trailing(),null,null);
        workspace.bytes(workspace.runPath(run.id,"rawdata/original.xlsx"),bytes);
        run.workbook.putAll(Map.of("filename",filename,"sha256",RuntimeWorkspace.hash(bytes),"month",prepared.month(),"currentSheet",currentSheet,"historySheets",prepared.historySheets(),"windowStart",prepared.windowStart(),"windowEnd",prepared.windowEnd(),"missingBaselines",prepared.missingBaselines(),"csvEncoding","UTF-8"));
        run.workbook.put("linkedInCurrentRows",linkedInRows(prepared.current()));run.workbook.put("linkedInTrailingRows",linkedInRows(prepared.trailing()));
        run.workbook.put("blogRows",prepared.blogRows());run.workbook.put("blogSkippedRows",prepared.blogSkippedRows());
        Pipeline.event(run,"WorkbookPreparationAgent","Prepared structured workbook rows without intermediate CSV files; "+prepared.missingBaselines()+" historical activities need baseline review");
        workspace.save(run);return run;
    }
static long linkedInRows(Data data) {
    if(data==null)return 0;
    return data.rows.stream().filter(row->{try {var uri=java.net.URI.create(row.get(URL).strip());String host=uri.getHost();return host!=null&&(host.equalsIgnoreCase("linkedin.com")||host.toLowerCase(Locale.ROOT).endsWith(".linkedin.com"))&&uri.getPath().matches("/(events|posts|feed/update|pulse)/.*");}catch(Exception invalid){return false;}}).count();
}
public synchronized Run createSharePointWorkbooks(byte[] currentBytes,String currentFilename,byte[] historyBytes,String historyFilename,String month,String currentSheet,List<String> historySheets,String currentUrl,String historyUrl) throws Exception {
    return createSharePointWorkbooks(currentBytes,currentFilename,historyBytes,historyFilename,month,currentSheet,historySheets,currentUrl,historyUrl,null);
}
public synchronized Run createSharePointWorkbooks(byte[] currentBytes,String currentFilename,byte[] historyBytes,String historyFilename,String month,String currentSheet,List<String> historySheets,String currentUrl,String historyUrl,String chartHistoryId) throws Exception {
    if(chartHistoryId!=null&&!chartHistoryId.isBlank())chartHistoryStore.load(chartHistoryId);
    Run run=createWorkbook(new com.ibm.advocacy.model.WorkbookRequest(currentBytes,month,currentSheet,historySheets,historyBytes),currentFilename,historyFilename);
    workspace.bytes(workspace.runPath(run.id,"rawdata/trailing-original.xlsx"),historyBytes);
    run.workbook.put("source","SharePoint");run.workbook.put("sourceUrl",currentUrl);
    run.workbook.put("historySourceUrl",historyUrl);run.workbook.put("historyFilename",historyFilename);run.workbook.put("historySha256",RuntimeWorkspace.hash(historyBytes));
    run.workbook.put("reportingPolicy","MONTH_END");run.workbook.put("metricModel","DA metric model 2026");
    run.workbook.put("publicationCheckpoint",true);
    if(chartHistoryId!=null&&!chartHistoryId.isBlank())chartHistoryStore.attach(run,chartHistoryId);
    workspace.save(run);return run;
}
public synchronized byte[] input(String id,String name) throws Exception {

        if(!Set.of("AvocadoBot 2.csv","trailing_90-day.csv").contains(name))throw new BadRequestException("Unknown input file");
        Run run=get(id);
        if(!run.workbook.isEmpty()) {
            if(name.equals("trailing_90-day.csv")&&!run.trailing)throw new NotFoundException("Input is not available for this run");
            return csv.write(workspace.data(id,name.equals("AvocadoBot 2.csv")?"source":"trailing-source"));
        }
        Path path=workspace.runPath(id,"rawdata/"+name);
        if(!Files.exists(path))throw new NotFoundException("Input is not available for this run");
        return Files.readAllBytes(path);
    }
    public synchronized Run create(byte[] current,byte[] trailing) throws Exception { return create(current,trailing,false); }
    private Run create(byte[] current,byte[] trailing,boolean utf8Trailing) throws Exception {
        if(current.length>6_000_000||(trailing!=null&&trailing.length>6_000_000))throw new BadRequestException("Each CSV must be smaller than 6 MB");
        Data source=csv.read(current,false),past=trailing==null?null:csv.read(trailing,true,utf8Trailing?StandardCharsets.UTF_8:java.nio.charset.Charset.forName("windows-1252"));
        return createPrepared(source,past,current,trailing);
    }
    /** Workbook rows stay structured; CSV snapshots are generated in memory only when downloaded. */
    private Run createPrepared(Data source,Data past,byte[] current,byte[] trailing) throws Exception {
        if(source.rows.isEmpty())throw new BadRequestException("Current-month input has no activity rows");
        String instructions=Files.readString(workspace.path("RUN.md"))+"\n"+Files.readString(workspace.path("AGENTS.md"));
        if(!RuntimeWorkspace.hash(instructions.getBytes(StandardCharsets.UTF_8)).equals(supportedInstructions))throw new BadRequestException("RUN.md or AGENTS.md changed. Review the new rules and update the implemented contract and its supported instruction hash before importing.");
        for(String required:List.of("Phase 1:","Phase 2:","Phase 3:","Phase 4:","Phase 5:","Phase 6:","Phase 7:","Video Views"))if(!instructions.contains(required))throw new BadRequestException("Unsupported instructions: missing "+required);
        Run run=new Run();run.id=UUID.randomUUID().toString();run.createdAt=Instant.now().toString();run.currentRows=source.rows.size();run.trailing=past!=null;run.trailingRows=past==null?0:past.rows.size();
        run.instructionHash=RuntimeWorkspace.hash(instructions.getBytes(StandardCharsets.UTF_8));run.inputHash=RuntimeWorkspace.hash(current==null?results.encode(source):current);
        if(current!=null)workspace.bytes(workspace.runPath(run.id,"rawdata/AvocadoBot 2.csv"),current);
        if(trailing!=null)workspace.bytes(workspace.runPath(run.id,"rawdata/trailing_90-day.csv"),trailing);
        for(String name:List.of("RUN.md","AGENTS.md"))workspace.bytes(workspace.runPath(run.id,"instructions/"+name),Files.readAllBytes(workspace.path(name)));
        workspace.data(run.id,"source",source);if(past!=null)workspace.data(run.id,"trailing-source",past);
        Pipeline.event(run,"Import","Imported "+run.currentRows+" current-month and "+run.trailingRows+" trailing rows; raw uploads preserved");workspace.save(run);return run;
    }
    public synchronized Run get(String id) throws Exception {
        synchronized(lock(id)) {
            Run run=workspace.load(id);
            if(!run.workbook.isEmpty()) {
                boolean changed=false;
                for(String source:List.of("source","trailing-source")) {
                    String key=source.equals("source")?"linkedInCurrentRows":"linkedInTrailingRows";
                    if(!run.workbook.containsKey(key)&&Files.exists(workspace.runPath(id,"state/"+source+".json"))) {
                        run.workbook.put(key,linkedInRows(workspace.data(id,source)));changed=true;
                    }
                }
                if(changed)workspace.save(run);
            }
            if(run.status.equals("RUNNING_PHASE")&&!active.contains(id)) { run.status="FAILED_RETRYABLE";run.error="Execution interrupted by restart; retry resumes from immutable phase inputs";workspace.save(run); }
            if(run.status.equals("PUBLISHING_WORKBOOK")&&!active.contains(id)){run.status=run.phase==3?"AWAITING_PUBLICATION":"COMPLETED";run.error="Publication interrupted by restart. Preview the workbook to verify its latest state before publishing again.";if(run.publication!=null){run.publication.status="FAILED";run.publication.message=run.error;}workspace.save(run);}
            return run;
        }
    }
    public synchronized List<Run> list() throws Exception {
        Path runs=workspace.path("runs");if(!Files.exists(runs))return List.of();
        try(var dirs=Files.list(runs)) { List<Run> result=new ArrayList<>();for(Path dir:dirs.filter(p->!Files.isSymbolicLink(p)&&Files.isDirectory(p)).toList())try { result.add(get(dir.getFileName().toString())); }catch(Exception ignored) {}return result.stream().sorted(Comparator.comparing((Run r)->r.createdAt).reversed()).limit(30).toList(); }
    }
    public synchronized Run start(String id,boolean accepted) throws Exception {
        return start(id,accepted,false);
    }
    public synchronized Run start(String id,boolean accepted,boolean automaticProcessing) throws Exception {
        synchronized(lock(id)) { Run run=get(id);if(!run.status.equals("READY"))throw new ClientErrorException("Run is already started",409);if(!accepted)throw new BadRequestException("The implemented calculation contract must be enabled before starting");run.contractAccepted=true;run.automaticProcessing=automaticProcessing;Pipeline.event(run,"Processing","Started "+(automaticProcessing?"automatic processing; clean phases advance, unresolved items require review":"with phase review checkpoints"));enqueue(run,1);return run; }
    }
    /** Serialize reset against imports/actions and reject active background work. */
    public synchronized int resetHistory(boolean confirmed) throws Exception {
        if(!confirmed)throw new BadRequestException("Confirm that all saved run history and generated outputs should be deleted");
        if(!active.isEmpty())throw new ClientErrorException("A run is still processing. Wait for it to finish or cancel it before resetting history.",409);
        int deleted=workspace.resetRuns();
        locks.clear();cancelled.clear();
        return deleted;
    }
    /** Delete an idle run without blocking on or altering another run's worker. */
    public synchronized void deleteRun(String id,boolean confirmed) throws Exception {
        if(!confirmed)throw new BadRequestException("Confirm that this saved run and its generated outputs should be deleted");
        synchronized(lock(id)) {
            get(id);
            if(active.contains(id))throw new ClientErrorException("This run is still processing or publishing. Wait for it to finish or cancel it before deleting it.",409);
            workspace.deleteRun(id);
            cancelled.remove(id);locks.remove(id);
        }
    }
    public static String digest(Run run) { return RuntimeWorkspace.hash((run.phase+":"+run.version+":"+run.artifacts.values().stream().map(a->a.name()+":"+a.sha256()).sorted().toList()).getBytes(StandardCharsets.UTF_8)); }
    public synchronized Run approve(String id,int phase,int version,String digest,boolean acknowledge) throws Exception {
        return approve(id,phase,version,digest,acknowledge,false);
    }
    public synchronized Run approve(String id,int phase,int version,String digest,boolean acknowledge,boolean automaticProcessing) throws Exception {
        synchronized(lock(id)) {
            Run run=get(id);if(!run.status.equals("AWAITING_APPROVAL")||run.phase!=phase||run.version!=version||!digest(run).equals(digest))throw new ClientErrorException("Approval is stale or this phase is not awaiting approval",409);
            if(run.issues.stream().anyMatch(Issue::blocking))throw new BadRequestException("Resolve invalid numeric data before approving");
            if(!run.issues.isEmpty()&&!acknowledge)throw new BadRequestException("Acknowledge unresolved collection issues before approving");
            run.automaticProcessing|=automaticProcessing;
            boolean automatic=automaticProcessing&&run.issues.isEmpty();run.approvals.add(new Approval(phase,version,digest,Instant.now().toString(),acknowledge,automatic));Pipeline.event(run,automatic?"Automatic progression":"Approval",(automatic?"Automatically advanced clean phase ":"Approved phase ")+phase+" version "+version);if(phase==3&&Boolean.TRUE.equals(run.workbook.get("publicationCheckpoint"))){run.status="AWAITING_PUBLICATION";run.review="Publish approved current inputs to the metrics workbook before trailing collection";workspace.save(run);}else enqueue(run,phase+1);return run;
        }
    }
    void enqueue(Run run,int phase) throws Exception {
        if(!active.add(run.id))throw new ClientErrorException("Run is busy",409);
        run.phase=phase;run.status="RUNNING_PHASE";run.error="";run.collectionProgress=null;workspace.save(run);
        workers.submit(()->execute(run.id,phase));
    }
    void execute(String id,int phase) {
        boolean ownsWork=true;
        try {
            int current=phase;
            while(current<=7) {
                if(cancelled.contains(id))break;
                workflow.execute(id+":"+current+":"+UUID.randomUUID(),new PhaseTask(id,current));
                synchronized(lock(id)) {
                    Run run=workspace.load(id);
                    if(cancelled.contains(id)) { run.status="CANCELLED";workspace.save(run);active.remove(id);ownsWork=false;return; }
                    if(current<=4&&(current!=4||run.trailing)&&run.automaticProcessing&&run.issues.isEmpty()) {
                        run.approvals.add(new Approval(current,run.version,digest(run),Instant.now().toString(),false,true));
                        Pipeline.event(run,"Automatic progression","Phase "+current+" completed with no review items; advanced automatically");
                        if(current==3&&Boolean.TRUE.equals(run.workbook.get("publicationCheckpoint"))) {
                            run.status="AWAITING_PUBLICATION";run.review="Review and publish current inputs before trailing collection";workspace.save(run);active.remove(id);ownsWork=false;return;
                        }
                        current++;run.phase=current;workspace.save(run);continue;
                    }
                    // Publish readiness and release ownership under the same lock used by approval.
                    // The old worker must not remove a newly approved worker's active entry in finally.
                    if(current<=4&&(current!=4||run.trailing)) { run.status="AWAITING_APPROVAL";workspace.save(run);active.remove(id);ownsWork=false;return; }
                    if(current==7) { run.status="COMPLETED";workspace.save(run);active.remove(id);ownsWork=false;return; }
                    current++;run.phase=current;workspace.save(run);
                }
            }
            synchronized(lock(id)) { Run run=workspace.load(id);run.status="CANCELLED";workspace.save(run);active.remove(id);ownsWork=false; }
        } catch(Exception e) {
            org.jboss.logging.Logger.getLogger(RunService.class).error("Agent phase "+phase+" failed for "+id,e);
            try { synchronized(lock(id)) { Run run=workspace.load(id);run.status=cancelled.contains(id)?"CANCELLED":"FAILED_RETRYABLE";run.error=Pipeline.message(e);Pipeline.event(run,"RunOrchestrator",run.error);workspace.save(run);active.remove(id);ownsWork=false; } }catch(Exception ignored) {}
        } finally { if(ownsWork)synchronized(lock(id)){active.remove(id);} }
    }
    public synchronized Run retry(String id) throws Exception {
        synchronized(lock(id)) { Run run=get(id);if(!run.status.equals("FAILED_RETRYABLE")&&!(run.status.equals("AWAITING_APPROVAL")&&Set.of(1,4).contains(run.phase)))throw new ClientErrorException("Retry an interrupted phase or a current collection review",409);cancelled.remove(id);Pipeline.event(run,"Collection retry","Retrying phase "+run.phase+" from saved source rows; successful and manual measurements are reused, failed requests are attempted again");enqueue(run,run.phase);return run; }
    }
    public synchronized Run cancel(String id) throws Exception {
        synchronized(lock(id)) { Run run=get(id);if(Set.of("COMPLETED","CANCELLED").contains(run.status))throw new ClientErrorException("Run is already finished",409);cancelled.add(id);if(!active.contains(id)){run.status="CANCELLED";workspace.save(run);}else run.status="CANCELLING";return run; }
    }
    public boolean cancelled(String id) { return cancelled.contains(id); }
    void publicationStarted(Run run) throws Exception {
        if(!active.add(run.id))throw new ClientErrorException("Run is busy",409);
        run.status="PUBLISHING_WORKBOOK";run.error="";workspace.save(run);
    }
    void publicationFinished(String id,boolean beforeTrailing) throws Exception {
        synchronized(lock(id)){Run run=workspace.load(id);active.remove(id);if(cancelled.contains(id)){run.status="CANCELLED";workspace.save(run);}else if(beforeTrailing)enqueue(run,4);else{run.status="COMPLETED";workspace.save(run);}}
    }
    void publicationFailed(String id,boolean beforeTrailing,String message) throws Exception {
        synchronized(lock(id)){Run run=workspace.load(id);active.remove(id);run.status=cancelled.contains(id)?"CANCELLED":beforeTrailing?"AWAITING_PUBLICATION":"COMPLETED";run.error=message;if(run.publication!=null){run.publication.status="FAILED";run.publication.message=message;}Pipeline.event(run,"Workbook publication",message);workspace.save(run);}
    }
    public synchronized Run measured(String id,int version,String rowId,String value,String type,String evidence) throws Exception {
        return measured(id,version,rowId,value,type,evidence,false);
    }
    public synchronized Run measured(String id,int version,String rowId,String value,String type,String evidence,boolean attributed) throws Exception {
        synchronized(lock(id)) {
            Run run=get(id);if(!run.status.equals("AWAITING_APPROVAL")||run.version!=version||!Set.of(1,2,4).contains(run.phase))throw new ClientErrorException("Measurements require the current phase-1, phase-2 or phase-4 review",409);
            BigDecimalValidation(value);if(evidence!=null&&evidence.length()>1000)throw new BadRequestException("Source / collection note must be at most 1,000 characters");
            evidence=evidence==null||evidence.isBlank()?"User confirmed measurement; source / collection note not provided":evidence.strip();
            Data source=workspace.data(id,run.phase==4?"trailing-source":"source");Row row=source.rows.stream().filter(r->r.id.equals(rowId)).findFirst().orElseThrow(()->new BadRequestException("Unknown source row"));
            if(run.phase==1&&!DIGITAL.contains(row.type()))throw new BadRequestException("This is not a digital activity");
            if(run.phase==2&&(!PERSON.contains(row.type())||row.type().equals("booth duty")))throw new BadRequestException("Booth lead recalculation requires a separate request");
            String expected=run.phase==2?"ATTENDANCE":MetricCollectors.type(row.get(URL));
            boolean correction=run.issues.stream().anyMatch(i->i.rowId().equals(rowId)&&i.blocking()&&i.metricType().equals(type));
            if(!expected.equals(type)&&!correction)throw new BadRequestException("Required measurement type: "+expected);
            String key=type.equals("DURATION")?"duration:"+row.id:type.equals("BASELINE")?"baseline:"+row.id:run.phase==2?"attendance:"+row.id:Calculations.urlKey(row.get(URL));if(key.isBlank())key="blank:"+row.id;
            if(attributed) {
                if(run.phase!=4||Set.of("BASELINE","DURATION").contains(type))throw new BadRequestException("Attributed totals are available only for historical resource measurements");
                value=Calculations.format(Calculations.number(value).multiply(java.math.BigDecimal.valueOf(historicalGroupSize(source,row))));
            }
            run.measurements.put(key,new Measurement(Calculations.format(Calculations.number(value)),type,row.get(URL),Instant.now().toString(),evidence,true));
            Pipeline.event(run,"Manual collection","Recorded "+type+" for "+row.id+"; refreshing review artifacts");enqueue(run,run.phase);return run;
        }
    }
    private static String editableNumber(String value){try{return Calculations.format(Calculations.number(value));}catch(IllegalArgumentException invalid){return "";}}
public record SourceContext(String workbook,String sheet,String rowId,String author,String tool) {}
private static SourceContext sourceContext(Run run,Row row) {
    String filename=row.get("Source workbook");if(filename.isBlank())filename=String.valueOf(run.workbook.getOrDefault(run.phase==4?"historyFilename":"filename",run.phase==4?"Historical input":"Current input"));
    return new SourceContext(filename,row.get("Source sheet"),row.id,row.get("Author of activity").isBlank()?row.get("Author"):row.get("Author of activity"),row.get("Tool"));
}
private static long historicalGroupSize(Data data,Row row) {
    String key=Calculations.urlKey(row.get(URL));
    return key.isBlank()?1:Math.max(1,data.rows.stream().filter(TrailingEligibility::includes).filter(other->Calculations.urlKey(other.get(URL)).equals(key)).count());
}
public record MeasurementInput(String originalViews,String originalDuration,String baseline,String views,String duration,String value,boolean canRetain,SourceContext source,boolean hasDuration,boolean attributed) {}
public synchronized MeasurementInput measurementInput(String id,String rowId,String type) throws Exception {
    Run run=get(id);
    if(!Set.of(1,2,4).contains(run.phase))throw new BadRequestException("Original measurement context is available during phases 1, 2, and 4");
    if(type==null||!Set.of("VIEWS","VIDEO_VIEWS","REACTIONS","STARS","DOWNLOADS","ATTENDANCE","DURATION","BASELINE").contains(type))throw new BadRequestException("Unknown measurement type");
    Data data=workspace.data(id,run.phase==4?"trailing-source":"source");Row row=data.rows.stream().filter(r->r.id.equals(rowId)).findFirst().orElseThrow(()->new BadRequestException("Unknown source row"));
    String initial=switch(type){case "DURATION"->row.get("Duration (in hours)");case "BASELINE"->TrailingEligibility.baseline(row);case "REACTIONS"->row.get("total_reactions").isBlank()?row.get(VIEWS):row.get("total_reactions");default->row.get(VIEWS);};
    String key=type.equals("DURATION")?"duration:"+row.id:type.equals("BASELINE")?"baseline:"+row.id:run.phase==2?"attendance:"+row.id:Calculations.urlKey(row.get(URL));if(key.isBlank())key="blank:"+row.id;
    Measurement saved=run.measurements.get(key);
    boolean attributed=run.phase==4&&!Set.of("BASELINE","DURATION").contains(type);
    if(attributed&&!editableNumber(TrailingEligibility.baseline(row)).isBlank())initial=TrailingEligibility.baseline(row);
    String selected=saved==null?initial:saved.value();
    if(attributed&&saved!=null)selected=Calculations.format(Calculations.number(saved.value()).divide(java.math.BigDecimal.valueOf(historicalGroupSize(data,row)),6,java.math.RoundingMode.HALF_UP));
    return new MeasurementInput(row.get(VIEWS),row.get("Duration (in hours)"),TrailingEligibility.baseline(row),editableNumber(row.get(VIEWS)),editableNumber(row.get("Duration (in hours)")),editableNumber(selected),false,sourceContext(run,row),row.cells.containsKey("Duration (in hours)"),attributed);
}
    public record TrailingInput(String originalViews,String originalDuration,String baseline,String views,String duration,boolean canRetain,SourceContext source,boolean hasDuration) {}
    private Row trailingRow(Run run,String rowId) throws Exception {
        if(!run.trailing)throw new BadRequestException("This run has no historical input");
        Row row=workspace.data(run.id,"trailing-source").rows.stream().filter(r->r.id.equals(rowId)).findFirst().orElseThrow(()->new BadRequestException("Unknown historical row"));
        if(!row.get(URL).isBlank())throw new BadRequestException("Input retention is available for historical rows without a URL");
        return row;
    }
    public synchronized TrailingInput trailingInput(String id,String rowId) throws Exception {
        Run run=get(id);Row row=trailingRow(run,rowId);var reviewed=run.inputReviews.get(rowId);boolean valid=true;
        try{BigDecimalValidation(row.get(VIEWS));if(row.cells.containsKey("Duration (in hours)"))BigDecimalValidation(row.get("Duration (in hours)"));}catch(IllegalArgumentException|BadRequestException e){valid=false;}
        return new TrailingInput(row.get(VIEWS),row.get("Duration (in hours)"),TrailingEligibility.baseline(row),reviewed==null?editableNumber(row.get(VIEWS)):reviewed.views(),reviewed==null?editableNumber(row.get("Duration (in hours)")):reviewed.duration(),valid,sourceContext(run,row),row.cells.containsKey("Duration (in hours)"));
    }
    public synchronized Run reviewTrailingInput(String id,int version,String rowId,String views,String duration,boolean keepOriginal,String note) throws Exception {
        synchronized(lock(id)) {
            Run run=get(id);
            if(!run.status.equals("AWAITING_APPROVAL")||run.phase!=4||run.version!=version)throw new ClientErrorException("Input review requires the current trailing phase review",409);
            Row row=trailingRow(run,rowId);
            boolean hasDuration=row.cells.containsKey("Duration (in hours)");String count=keepOriginal?row.get(VIEWS):views,hours=hasDuration?(keepOriginal?row.get("Duration (in hours)"):duration):"";
            try{BigDecimalValidation(count);if(hasDuration)BigDecimalValidation(hours);}catch(IllegalArgumentException e){throw new BadRequestException(hasDuration?"Enter valid nonnegative views / attendance and duration":"Enter valid nonnegative views / attendance");}
            if(note!=null&&note.length()>1000)throw new BadRequestException("Review note must be at most 1,000 characters");
            String evidence=note==null||note.isBlank()?keepOriginal?"User retained original historical inputs":"User reviewed and edited historical inputs":note.strip();
            run.inputReviews.put(rowId,new com.ibm.advocacy.model.InputReview(Calculations.format(Calculations.number(count)),hasDuration?Calculations.format(Calculations.number(hours)):"",evidence,!keepOriginal));
            Pipeline.event(run,"Input review",(keepOriginal?"Retained":"Edited")+" supplied attendance/views"+(hasDuration?" and duration":"")+" for "+rowId+"; no fresh lifetime total or delta claimed");enqueue(run,4);return run;
        }
    }
    static void BigDecimalValidation(String value) { if(Calculations.number(value).signum()<0)throw new BadRequestException("Measured totals cannot be negative"); }
    /** CSV is produced only for an explicit export; previews use artifactData directly. */
    public synchronized byte[] artifact(String id,String name,Integer version) throws Exception {
        return csv.write(artifactData(id,name,version));
    }
    public synchronized Data artifactData(String id,String name,Integer version) throws Exception {
        if(!OUTPUTS.contains(name))throw new BadRequestException("Unknown artifact");Run run=get(id);Artifact artifact=run.artifacts.get(name);if(artifact==null)throw new NotFoundException("Artifact is not generated yet");
        int chosen=version==null?artifact.version():version;if(chosen<1||chosen>run.version)throw new BadRequestException("Invalid artifact version");
        Data data=results.load(id,name,chosen);
        if(name.equals("updated_90-day.csv")) {
            Artifact current=run.artifacts.get("current.csv");
            data=TrailingReporting.enrich(data,current==null?null:results.load(id,"current.csv",current.version()));
        }
        if(name.equals("current.csv")||name.equals("updated_90-day.csv"))data=reporting.artifact(run,data,name.equals("current.csv"));
        return Calculations.reported(data,name.equals("current.csv")&&!"MONTH_END".equals(run.workbook.get("reportingPolicy")));
    }
    public synchronized MonthlyReporting.Report report(String id,String scope) throws Exception {
        Run run=get(id);
        if(!run.workbook.containsKey("chartHistory")) {
            var saved=chartHistoryStore.cached(chartHistoryUrl);
            if(saved!=null){chartHistoryStore.attach(run,String.valueOf(saved.source().get("cacheId")));run.reportingRevision++;workspace.save(run);}
        }
        return reporting.build(run,rawArtifact(run,"current.csv"),rawArtifact(run,"updated_90-day.csv"),rawArtifact(run,"follow-up.csv"),scope);
    }
    public synchronized Run importChartHistory(String id,int revision,String url,String endMonth) throws Exception {
        Run run=get(id);
        if(!run.status.equals("COMPLETED"))throw new ClientErrorException("Complete the agent phases before reading chart history",409);
        if(run.reportingRevision!=revision)throw new ClientErrorException("Reporting changed; reload before reading chart history",409);
        var workbook=chartCollector.download(url);var snapshot=impactTrends.read(workbook.bytes(),workbook.filename(),endMonth);
        chartHistoryStore.save(workbook.bytes(),workbook.filename(),workbook.sourceUrl(),url,endMonth);
        Map<String,Object> source=new LinkedHashMap<>();source.put("filename",workbook.filename());source.put("sourceUrl",workbook.sourceUrl());source.put("sha256",RuntimeWorkspace.hash(workbook.bytes()));source.put("sheet",ImpactTrends.SHEET);source.put("endMonth",snapshot.endMonth());source.put("importedAt",Instant.now().toString());
        String snapshotFilename="chart-history-"+(run.reportingRevision+1)+".xlsx";source.put("snapshotFilename",snapshotFilename);
        workspace.bytes(workspace.runPath(id,"rawdata/"+snapshotFilename),workbook.bytes());run.workbook.put("chartHistory",source);run.reportingRevision++;Pipeline.event(run,"Historical chart input","Read "+workbook.filename()+" / "+ImpactTrends.SHEET+" ending "+snapshot.endMonth()+"; snapshot "+snapshotFilename);workspace.save(run);return run;
    }
    private Data rawArtifact(Run run,String name) throws Exception {
        Artifact artifact=run.artifacts.get(name);return artifact==null?new Data():results.load(run.id,name,artifact.version());
    }
    public synchronized byte[] reportingDownload(String id,String scope,boolean excel) throws Exception {
        return reportingDownload(id,scope,excel,false);
    }
    public synchronized byte[] reportingDownload(String id,String scope,boolean excel,boolean bob) throws Exception {
        MonthlyReporting.Report report=report(id,scope);return excel?reportingWorkbook.write(report,bob):csv.write(Calculations.reported(report.details(),false));
    }
    public synchronized Run confirmMonthEnd(String id,int revision,String contribution,String rowId,String value,String asOf,String note) throws Exception {
        synchronized(lock(id)) {
            Run run=get(id);
            if(!run.status.equals("COMPLETED"))throw new ClientErrorException("Complete the agent phases before confirming reporting values",409);
            if(run.reportingRevision!=revision)throw new ClientErrorException("Reporting values changed; reload the report before saving",409);
            if(!Set.of("current","trailing").contains(contribution))throw new BadRequestException("Unknown contribution");
            String month=String.valueOf(run.workbook.getOrDefault("month",""));
            if(month.isBlank()||!java.time.YearMonth.parse(month).atEndOfMonth().toString().equals(asOf))throw new BadRequestException("Measurement date must be the reporting month's last day");
            if(!java.time.LocalDate.parse(asOf).isBefore(java.time.LocalDate.now()))throw new BadRequestException("The reporting month must close before confirming a month-end total");
            BigDecimalValidation(value);if(note!=null&&note.length()>1000)throw new BadRequestException("Month-end evidence note must be at most 1000 characters");
            note=note==null||note.isBlank()?"User confirmed total as of "+asOf:note.strip();
            Data data=rawArtifact(run,contribution.equals("current")?"current.csv":"updated_90-day.csv");
            if(data.rows.stream().noneMatch(row->row.id.equals(rowId)))throw new BadRequestException("Unknown reporting row");
            Row reportingRow=reporting.build(run,rawArtifact(run,"current.csv"),rawArtifact(run,"updated_90-day.csv"),rawArtifact(run,"follow-up.csv"),contribution).details().rows.stream().filter(row->row.id.equals(rowId)).findFirst().orElseThrow(()->new BadRequestException("Unknown reporting row"));
            String baseline=reportingRow.get("Reporting baseline");
            if(!baseline.isBlank()&&Calculations.number(value).compareTo(Calculations.number(baseline))<0)
                throw new BadRequestException("Attributed total "+Calculations.format(Calculations.number(value))+" is below previous baseline "+baseline+". Checking the confirmation box cannot resolve negative growth. Verify the total and its author/tool share; if the baseline or allocation is wrong, correct the source workbook and start a new run. No reporting changes were saved.");
            run.monthEndValues.put(contribution+":"+rowId,new com.ibm.advocacy.model.MonthEndValue(Calculations.format(Calculations.number(value)),asOf,note.strip(),Instant.now().toString()));
            if(run.publication!=null&&run.publication.stage.equals("COMPLETED")){run.publication.status="STALE";run.publication.message="Reporting totals changed. Publish the completed snapshot again.";}
            run.reportingRevision++;Pipeline.event(run,"Month-end reporting","Confirmed "+contribution+" total for "+rowId+" as of "+asOf);workspace.save(run);return run;
        }
    }
    public synchronized byte[] zip(String id) throws Exception {
        Run run=get(id);ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(out)) { for(Artifact artifact:run.artifacts.values()) { zip.putNextEntry(new ZipEntry(artifact.name()));zip.write(artifact(id,artifact.name(),artifact.version()));zip.closeEntry(); } }
        return out.toByteArray();
    }
}
