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
    @Inject PhaseWorkflow workflow;
    @Inject com.ibm.advocacy.agentic.WorkbookWorkflow workbookWorkflow;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="metrics.instructions.sha256") String supportedInstructions;
    private final ExecutorService workers=Executors.newFixedThreadPool(3);
    private final ConcurrentMap<String,Object> locks=new ConcurrentHashMap<>();
    private final Set<String> active=ConcurrentHashMap.newKeySet(),cancelled=ConcurrentHashMap.newKeySet();
    Object lock(String id) { return locks.computeIfAbsent(id,k->new Object()); }
    @PreDestroy void close() { workers.shutdownNow(); }
    public synchronized Run createWorkbook(byte[] bytes,String filename,String month,String currentSheet,List<String> historySheets) throws Exception {
com.ibm.advocacy.model.PreparedWorkbook prepared;
try { prepared=workbookWorkflow.execute(UUID.randomUUID().toString(),new com.ibm.advocacy.model.WorkbookRequest(bytes,month,currentSheet,historySheets)); }
catch(RuntimeException failure) {
    // Agent reflection wrappers must preserve actionable 4xx validation errors.
    for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof jakarta.ws.rs.WebApplicationException web)throw web;
    throw failure;
}
        byte[] current=csv.write(prepared.current()),trailing=prepared.trailing()==null?null:csv.write(prepared.trailing());
        Run run=create(current,trailing,true);
        workspace.bytes(workspace.runPath(run.id,"rawdata/original.xlsx"),bytes);
        run.workbook.putAll(Map.of("filename",filename,"sha256",RuntimeWorkspace.hash(bytes),"month",prepared.month(),"currentSheet",currentSheet,"historySheets",prepared.historySheets(),"windowStart",prepared.windowStart(),"windowEnd",prepared.windowEnd(),"missingBaselines",prepared.missingBaselines(),"csvEncoding","UTF-8"));
        Pipeline.event(run,"WorkbookPreparationAgent","Generated current-month and trailing inputs from Excel; "+prepared.missingBaselines()+" historical activities need baseline review");
        workspace.save(run);return run;
    }
public synchronized Run createSharePointWorkbook(byte[] bytes,String filename,String month,String currentSheet,List<String> historySheets,String sourceUrl) throws Exception {
    Run run=createWorkbook(bytes,filename,month,currentSheet,historySheets);
    run.workbook.put("source","SharePoint");run.workbook.put("sourceUrl",sourceUrl);workspace.save(run);return run;
}
public synchronized byte[] input(String id,String name) throws Exception {

        if(!Set.of("AvocadoBot 2.csv","trailing_90-day.csv").contains(name))throw new BadRequestException("Unknown input file");
        get(id);Path path=workspace.runPath(id,"rawdata/"+name);
        if(!Files.exists(path))throw new NotFoundException("Input is not available for this run");
        return Files.readAllBytes(path);
    }
    public synchronized Run create(byte[] current,byte[] trailing) throws Exception { return create(current,trailing,false); }
    private Run create(byte[] current,byte[] trailing,boolean utf8Trailing) throws Exception {
        if(current.length>6_000_000||(trailing!=null&&trailing.length>6_000_000))throw new BadRequestException("Each CSV must be smaller than 6 MB");
        Data source=csv.read(current,false),past=trailing==null?null:csv.read(trailing,true,utf8Trailing?StandardCharsets.UTF_8:java.nio.charset.Charset.forName("windows-1252"));
        if(source.rows.isEmpty())throw new BadRequestException("Current-month input has no activity rows");
        String instructions=Files.readString(workspace.path("RUN.md"))+"\n"+Files.readString(workspace.path("AGENTS.md"));
        if(!RuntimeWorkspace.hash(instructions.getBytes(StandardCharsets.UTF_8)).equals(supportedInstructions))throw new BadRequestException("RUN.md or AGENTS.md changed. Review the new rules and update the implemented contract and its supported instruction hash before importing.");
        for(String required:List.of("Phase 1:","Phase 2:","Phase 3:","Phase 4:","Phase 5:","Phase 6:","Phase 7:","Video Views"))if(!instructions.contains(required))throw new BadRequestException("Unsupported instructions: missing "+required);
        Run run=new Run();run.id=UUID.randomUUID().toString();run.createdAt=Instant.now().toString();run.currentRows=source.rows.size();run.trailing=past!=null;run.trailingRows=past==null?0:past.rows.size();
        run.instructionHash=RuntimeWorkspace.hash(instructions.getBytes(StandardCharsets.UTF_8));run.inputHash=RuntimeWorkspace.hash(current);
        workspace.bytes(workspace.runPath(run.id,"rawdata/AvocadoBot 2.csv"),current);
        if(trailing!=null)workspace.bytes(workspace.runPath(run.id,"rawdata/trailing_90-day.csv"),trailing);
        for(String name:List.of("RUN.md","AGENTS.md"))workspace.bytes(workspace.runPath(run.id,"instructions/"+name),Files.readAllBytes(workspace.path(name)));
        workspace.data(run.id,"source",source);if(past!=null)workspace.data(run.id,"trailing-source",past);
        Pipeline.event(run,"Import","Imported "+run.currentRows+" current-month and "+run.trailingRows+" trailing rows; raw uploads preserved");workspace.save(run);return run;
    }
    public synchronized Run seed() throws Exception { Path past=workspace.path("trailing_90-day.csv");return create(Files.readAllBytes(workspace.path("AvocadoBot 2.csv")),Files.exists(past)?Files.readAllBytes(past):null); }
    public synchronized Run get(String id) throws Exception {
        synchronized(lock(id)) {
            Run run=workspace.load(id);
            if(run.status.equals("RUNNING_PHASE")&&!active.contains(id)) { run.status="FAILED_RETRYABLE";run.error="Execution interrupted by restart; retry resumes from immutable phase inputs";workspace.save(run); }
            return run;
        }
    }
    public synchronized List<Run> list() throws Exception {
        Path runs=workspace.path("runs");if(!Files.exists(runs))return List.of();
        try(var dirs=Files.list(runs)) { List<Run> result=new ArrayList<>();for(Path dir:dirs.filter(p->!Files.isSymbolicLink(p)&&Files.isDirectory(p)).toList())try { result.add(get(dir.getFileName().toString())); }catch(Exception ignored) {}return result.stream().sorted(Comparator.comparing((Run r)->r.createdAt).reversed()).limit(30).toList(); }
    }
    public synchronized Run start(String id,boolean accepted) throws Exception {
        synchronized(lock(id)) { Run run=get(id);if(!run.status.equals("READY"))throw new ClientErrorException("Run is already started",409);if(!accepted)throw new BadRequestException("Accept the displayed calculation contract before starting");run.contractAccepted=true;enqueue(run,1);return run; }
    }
    /** Serialize reset against imports/actions and reject active background work. */
    public synchronized int resetHistory(boolean confirmed) throws Exception {
        if(!confirmed)throw new BadRequestException("Confirm that all saved run history and generated outputs should be deleted");
        if(!active.isEmpty())throw new ClientErrorException("A run is still processing. Wait for it to finish or cancel it before resetting history.",409);
        int deleted=workspace.resetRuns();
        locks.clear();cancelled.clear();
        return deleted;
    }
    public static String digest(Run run) { return RuntimeWorkspace.hash((run.phase+":"+run.version+":"+run.artifacts.values().stream().map(a->a.name()+":"+a.sha256()).sorted().toList()).getBytes(StandardCharsets.UTF_8)); }
    public synchronized Run approve(String id,int phase,int version,String digest,boolean acknowledge) throws Exception {
        synchronized(lock(id)) {
            Run run=get(id);if(!run.status.equals("AWAITING_APPROVAL")||run.phase!=phase||run.version!=version||!digest(run).equals(digest))throw new ClientErrorException("Approval is stale or this phase is not awaiting approval",409);
            if(run.issues.stream().anyMatch(Issue::blocking))throw new BadRequestException("Resolve invalid numeric data before approving");
            if(!run.issues.isEmpty()&&!acknowledge)throw new BadRequestException("Acknowledge unresolved collection issues before approving");
            run.approvals.add(new Approval(phase,version,digest,Instant.now().toString(),acknowledge));Pipeline.event(run,"Approval","Approved phase "+phase+" version "+version);enqueue(run,phase+1);return run;
        }
    }
    void enqueue(Run run,int phase) throws Exception {
        if(!active.add(run.id))throw new ClientErrorException("Run is busy",409);
        run.phase=phase;run.status="RUNNING_PHASE";run.error="";workspace.save(run);
        workers.submit(()->execute(run.id,phase));
    }
    void execute(String id,int phase) {
        try {
            int current=phase;
            while(current<=7) {
                if(cancelled.contains(id))break;
                workflow.execute(id+":"+current+":"+UUID.randomUUID(),new PhaseTask(id,current));
                synchronized(lock(id)) {
                    Run run=workspace.load(id);
                    if(cancelled.contains(id)) { run.status="CANCELLED";workspace.save(run);break; }
                    if(current<=4&&(current!=4||run.trailing)) { run.status="AWAITING_APPROVAL";workspace.save(run);return; }
                    if(current==7) { run.status="COMPLETED";workspace.save(run);return; }
                    current++;run.phase=current;workspace.save(run);
                }
            }
            synchronized(lock(id)) { Run run=workspace.load(id);run.status="CANCELLED";workspace.save(run); }
        } catch(Exception e) {
            org.jboss.logging.Logger.getLogger(RunService.class).error("Agent phase "+phase+" failed for "+id,e);
            try { synchronized(lock(id)) { Run run=workspace.load(id);run.status=cancelled.contains(id)?"CANCELLED":"FAILED_RETRYABLE";run.error=Pipeline.message(e);Pipeline.event(run,"RunOrchestrator",run.error);workspace.save(run); } }catch(Exception ignored) {}
        } finally { active.remove(id); }
    }
    public synchronized Run retry(String id) throws Exception {
        synchronized(lock(id)) { Run run=get(id);if(!run.status.equals("FAILED_RETRYABLE")&&!(run.status.equals("AWAITING_APPROVAL")&&Set.of(1,4).contains(run.phase)))throw new ClientErrorException("Retry an interrupted phase or a current collection review",409);cancelled.remove(id);enqueue(run,run.phase);return run; }
    }
    public synchronized Run cancel(String id) throws Exception {
        synchronized(lock(id)) { Run run=get(id);if(Set.of("COMPLETED","CANCELLED").contains(run.status))throw new ClientErrorException("Run is already finished",409);cancelled.add(id);if(!active.contains(id)){run.status="CANCELLED";workspace.save(run);}else run.status="CANCELLING";return run; }
    }
    public boolean cancelled(String id) { return cancelled.contains(id); }
    public synchronized Run measured(String id,int version,String rowId,String value,String type,String evidence) throws Exception {
        synchronized(lock(id)) {
            Run run=get(id);if(!run.status.equals("AWAITING_APPROVAL")||run.version!=version||!Set.of(1,2,4).contains(run.phase))throw new ClientErrorException("Measurements require the current phase-1, phase-2 or phase-4 review",409);
            BigDecimalValidation(value);if(evidence==null||evidence.isBlank())throw new BadRequestException("Record a source or collection note");
            Data source=workspace.data(id,run.phase==4?"trailing-source":"source");Row row=source.rows.stream().filter(r->r.id.equals(rowId)).findFirst().orElseThrow(()->new BadRequestException("Unknown source row"));
            if(run.phase==1&&!DIGITAL.contains(row.type()))throw new BadRequestException("This is not a digital activity");
            if(run.phase==2&&(!PERSON.contains(row.type())||row.type().equals("booth duty")))throw new BadRequestException("Booth lead recalculation requires a separate request");
            String expected=run.phase==2?"ATTENDANCE":MetricCollectors.type(row.get(URL));
            boolean correction=run.issues.stream().anyMatch(i->i.rowId().equals(rowId)&&i.blocking()&&i.metricType().equals(type));
            if(!expected.equals(type)&&!correction)throw new BadRequestException("Required measurement type: "+expected);
            String key=type.equals("DURATION")?"duration:"+row.id:type.equals("BASELINE")?"baseline:"+row.id:run.phase==2?"attendance:"+row.id:Calculations.urlKey(row.get(URL));if(key.isBlank())key="blank:"+row.id;
            run.measurements.put(key,new Measurement(Calculations.format(Calculations.number(value)),type,row.get(URL),Instant.now().toString(),evidence,true));
            Pipeline.event(run,"Manual collection","Recorded "+type+" for "+row.id+"; refreshing review artifacts");enqueue(run,run.phase);return run;
        }
    }
    static void BigDecimalValidation(String value) { if(Calculations.number(value).signum()<0)throw new BadRequestException("Measured totals cannot be negative"); }
    public synchronized byte[] artifact(String id,String name,Integer version) throws Exception {
        if(!OUTPUTS.contains(name))throw new BadRequestException("Unknown artifact");Run run=get(id);Artifact artifact=run.artifacts.get(name);if(artifact==null)throw new NotFoundException("Artifact is not generated yet");
        int chosen=version==null?artifact.version():version;if(chosen<1||chosen>run.version)throw new BadRequestException("Invalid artifact version");
        Path path=workspace.runPath(id,"output/"+chosen+"/"+name);if(!Files.exists(path))throw new NotFoundException("Artifact does not exist in this version");return Files.readAllBytes(path);
    }
    public synchronized byte[] zip(String id) throws Exception {
        Run run=get(id);ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(out)) { for(Artifact artifact:run.artifacts.values()) { zip.putNextEntry(new ZipEntry(artifact.name()));zip.write(artifact(id,artifact.name(),artifact.version()));zip.closeEntry(); } }
        return out.toByteArray();
    }
}
