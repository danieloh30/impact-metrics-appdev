package com.ibm.advocacy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.advocacy.collector.SharePointCollector;
import com.ibm.advocacy.model.*;
import com.ibm.advocacy.storage.*;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.ibm.advocacy.model.MetricsSchema.*;

/** Human review of a concrete upsert, followed by authenticated background publication. */
@ApplicationScoped
public class WorkbookPublicationService {
    @Inject RunService runs;
    @Inject RuntimeWorkspace workspace;
    @Inject ResultStore results;
    @Inject MonthlyReporting reporting;
    @Inject MonthlyInputWorkbook workbook;
    @Inject SharePointCollector sharepoint;
    @Inject ObjectMapper mapper;
    @Inject com.ibm.advocacy.agentic.PublicationWorkflow workflow;
    private final ExecutorService worker=Executors.newFixedThreadPool(2);
    @PreDestroy void close(){worker.shutdownNow();}
    public record Plan(WorkbookPublication publication,List<Row> rows,List<String> warnings) {}
    private record Stored(String url,String month,String stage,int version,int revision,String digest,String beforeHash,String afterHash,String candidateFilename,boolean beforeTrailing) {}
    private boolean beforeTrailing(Run run){return run.status.equals("AWAITING_PUBLICATION")&&run.phase==3;}
    private void allowed(Run run){if(!run.workbook.getOrDefault("source","").equals("SharePoint")||!Set.of("AWAITING_PUBLICATION","COMPLETED").contains(run.status)||run.status.equals("AWAITING_PUBLICATION")&&run.phase!=3)throw new ClientErrorException("Publish at the pre-trailing checkpoint or after the completed report",409);}
    private Data artifact(Run run,String name) throws Exception {Artifact a=run.artifacts.get(name);return a==null?new Data():results.load(run.id,name,a.version());}
    private Data currentRows(Run run) throws Exception {
        List<Row> rows=new ArrayList<>();Set<String> unresolved=new HashSet<>();
        for(String name:List.of("digital.csv","in-person.csv","follow-up.csv"))for(Row original:artifact(run,name).rows){Row row=Calculations.finalRow(original);for(String field:List.of("Source workbook","Source sheet","Measured at","Attendance hours"))if(original.cells.containsKey(field))row.cells.put(field,original.get(field));original.cells.forEach((field,value)->{if(field.startsWith("Participation "))row.cells.put(field,value);});row.cells.put("Current total",row.get(VIEWS));rows.add(row);if(name.equals("follow-up.csv"))unresolved.add(row.id);}
        for(Row original:workspace.data(run.id,"source").rows)if(!DIGITAL.contains(original.type())&&!PERSON.contains(original.type()))for(Row split:Calculations.split(original,null)){Row row=Calculations.finalRow(split);row.cells.put(NOTE,"Other activity — manual follow-up");rows.add(row);unresolved.add(row.id);}
        return reporting.currentPublication(run,Data.output(new Data(),rows),unresolved);
    }
    private record Snapshot(Data data,int skipped) {}
    private Snapshot snapshot(Run run,boolean before,List<String> warnings) throws Exception {
        Data processed=before?currentRows(run):runs.report(run.id,"combined").details();List<Row> rows=new ArrayList<>();Set<List<String>> seen=new HashSet<>();int skipped=0;
        if(!before&&processed.rows.stream().anyMatch(row->row.get("Reporting handling").equals("Needs attention")))throw new BadRequestException("Resolve the month-end reporting items before publishing a completed monthly snapshot. Reference-only rows do not block publication.");
        for(Row original:processed.rows){if(!original.get("Reporting status").equals("Included")){skipped++;continue;}Row row=original.copy();if(!seen.add(TrailingReporting.activityKey(row)))continue;String baseline=row.get("Pre-report Previous total");if(original.get("Contribution").equals("Current")||baseline.isBlank())baseline=row.get("Previous total");row.cells.put("Starting baseline",baseline.isBlank()?"0":baseline);rows.add(row);}
        if(skipped>0)warnings.add(skipped+" unresolved or duplicate contributions will not overwrite workbook values.");
        if(run.trailing){for(Row original:workspace.data(run.id,"trailing-source").rows){if(!seen.add(TrailingReporting.activityKey(original)))continue;try{if(Calculations.number(original.get("Current total")).signum()<0)continue;}catch(IllegalArgumentException invalid){continue;}Row carry=original.copy();carry.cells.put("Starting baseline",original.get("Current total"));carry.cells.put("Snapshot stage","BASELINE_CARRY");rows.add(carry);}}
        if(rows.isEmpty())throw new BadRequestException("No valid attributed activities are available to publish. Resolve current inputs first.");
        warnings.add(before?"Current inputs are approved; historical rows are carried as baselines, not newly measured growth. Phase 4 uses the frozen pre-publication baselines.":"Final totals use exact stored results. Unmatched existing workbook rows are retained.");
        return new Snapshot(Data.output(new Data(),rows),skipped);
    }
    public com.ibm.advocacy.collector.SharePointFileWriter.FileStatus status(String id) throws Exception {
        Run run=runs.get(id);if(!run.workbook.getOrDefault("source","").equals("SharePoint"))throw new BadRequestException("This run has no SharePoint workbook");
        return sharepoint.fileStatus(String.valueOf(run.workbook.get("historySourceUrl")));
    }
    public Plan preview(String id,int version,int revision) throws Exception {
        Run run=runs.get(id);allowed(run);if(run.version!=version||run.reportingRevision!=revision)throw new ClientErrorException("Results changed. Reload before previewing publication.",409);
        boolean before=beforeTrailing(run);List<String> warnings=new ArrayList<>();Snapshot prepared=snapshot(run,before,warnings);Data data=prepared.data();String url=String.valueOf(run.workbook.get("historySourceUrl"));var source=sharepoint.download(url);
        String month=String.valueOf(run.workbook.get("month")),stage=before?"CURRENT_INPUTS":"COMPLETED";var merge=workbook.merge(source.bytes(),data,month,stage,id);
        String beforeHash=RuntimeWorkspace.hash(source.bytes()),afterHash=RuntimeWorkspace.hash(merge.bytes());String digest=RuntimeWorkspace.hash((id+":"+version+":"+revision+":"+stage+":"+beforeHash+":"+afterHash).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        WorkbookPublication publication=new WorkbookPublication();publication.status="PREVIEW_READY";publication.stage=stage;publication.sheet=merge.sheet();publication.version=version;publication.revision=revision;publication.updated=merge.updated();publication.appended=merge.appended();publication.unchanged=merge.unchanged();publication.skipped=prepared.skipped();publication.createdSheet=merge.created();publication.planDigest=digest;publication.sourceSha256=beforeHash;publication.resultSha256=afterHash;publication.message="Review updates and additions. No existing rows will be deleted.";
        synchronized(runs.lock(id)){Run latest=workspace.load(id);allowed(latest);if(latest.version!=version||latest.reportingRevision!=revision)throw new ClientErrorException("Results changed during preview. Preview again.",409);String candidate="publication/candidate-"+digest+".xlsx";workspace.bytes(workspace.runPath(id,candidate),merge.bytes());workspace.bytes(workspace.runPath(id,"publication/before-"+digest+".xlsx"),source.bytes());workspace.bytes(workspace.runPath(id,"publication/plan.json"),mapper.writeValueAsBytes(new Stored(url,month,stage,version,revision,digest,beforeHash,afterHash,candidate,before)));latest.publication=publication;latest.error="";workspace.save(latest);}
        return new Plan(publication,data.rows.stream().limit(50).toList(),List.copyOf(warnings));
    }
    public Run publish(String id,int version,int revision,String digest,boolean confirmed) throws Exception {
        if(!confirmed)throw new BadRequestException("Confirm the displayed workbook update before publishing");
        synchronized(runs.lock(id)){Run run=workspace.load(id);allowed(run);Stored plan=mapper.readValue(Files.readAllBytes(workspace.runPath(id,"publication/plan.json")),Stored.class);if(run.version!=version||run.reportingRevision!=revision||plan.version()!=version||plan.revision()!=revision||!plan.digest().equals(digest)||run.publication==null||!run.publication.planDigest.equals(digest)||plan.beforeTrailing()!=beforeTrailing(run))throw new ClientErrorException("Publication preview is stale. Preview again.",409);run.publication.status="PUBLISHING";run.publication.message="Writing and verifying the metrics workbook…";runs.publicationStarted(run);worker.submit(()->{try{workflow.execute(id+":"+plan.digest(),new PublicationTask(id,plan.digest()));}catch(Exception error){try{runs.publicationFailed(id,plan.beforeTrailing(),Pipeline.message(error));}catch(Exception ignored){}}});return run;}
    }
    public WorkbookPublication commit(PublicationTask task) throws Exception {
        String id=task.runId();Stored plan=mapper.readValue(Files.readAllBytes(workspace.runPath(id,"publication/plan.json")),Stored.class);
        Run before=workspace.load(id);if(!plan.digest().equals(task.digest())||!before.status.equals("PUBLISHING_WORKBOOK")||before.publication==null||!before.publication.planDigest.equals(task.digest()))throw new ClientErrorException("Publication authorization is no longer current",409);
        if(runs.cancelled(id))throw new ClientErrorException("Publication cancelled before writing",409);
        byte[] candidate=Files.readAllBytes(workspace.runPath(id,plan.candidateFilename()));if(!RuntimeWorkspace.hash(candidate).equals(plan.afterHash()))throw new IllegalStateException("Publication candidate changed");
        var result=sharepoint.update(plan.url(),plan.beforeHash(),candidate);if(!RuntimeWorkspace.hash(result.bytes()).equals(plan.afterHash()))throw new IllegalStateException("Published workbook verification failed");
        WorkbookPublication receipt;
        synchronized(runs.lock(id)){Run run=workspace.load(id);run.publication.status="PUBLISHED";run.publication.message="Metrics workbook updated and verified. "+(plan.beforeTrailing()?"Starting trailing collection with preserved baselines.":"Completed monthly snapshot published.");run.publication.publishedAt=Instant.now().toString();run.publication.resultSha256=plan.afterHash();run.publication.revision=run.reportingRevision;receipt=run.publication;workspace.bytes(workspace.runPath(id,"publication/receipt-"+plan.digest()+".json"),mapper.writeValueAsBytes(receipt));Pipeline.event(run,"Workbook publication",receipt.message);workspace.save(run);}
        runs.publicationFinished(id,plan.beforeTrailing());return receipt;
    }
}
