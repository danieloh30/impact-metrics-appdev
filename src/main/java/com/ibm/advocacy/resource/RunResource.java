package com.ibm.advocacy.resource;

import com.ibm.advocacy.collector.LinkedInCollector;
import com.ibm.advocacy.csv.CsvCodec;
import com.ibm.advocacy.model.Artifact;
import com.ibm.advocacy.model.Data;
import com.ibm.advocacy.model.Run;
import com.ibm.advocacy.service.Pipeline;
import com.ibm.advocacy.service.RunService;

import io.smallrye.mutiny.Multi;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestStreamElementType;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import static com.ibm.advocacy.model.MetricsSchema.*;

@Path("/api") @Produces(MediaType.APPLICATION_JSON) @Blocking
public class RunResource {
    @Inject RunService service;
    @Inject CsvCodec csv;
    @Inject LinkedInCollector linkedin;
    public record Start(boolean acceptContract) {}
    public record Reset(boolean confirm) {}
    public record Approve(@Min(1) int version,@NotBlank String digest,boolean acknowledgeIssues) {}
    public record InputReview(@Min(1) int version,@NotBlank String rowId,String views,String duration,boolean keepOriginal,@Size(max=1000) String note) {}
    public record Manual(@Min(1) int version,@NotBlank String rowId,@NotBlank String value,@NotBlank String type,@NotBlank @Size(max=1000) String evidence) {}
    @GET @Path("/config") public Map<String,Object> config() { return Map.of("phases",PHASES,"contract",Pipeline.CONTRACT,"outputs",OUTPUTS); }
    @GET @Path("/linkedin/session") public Map<String,Object> session() { return linkedin.session(); }
    @POST @Path("/linkedin/session") public Response connect() { return Response.accepted(linkedin.connect()).build(); }
    @POST @Path("/runs/reset") @Consumes(MediaType.APPLICATION_JSON)
    public Map<String,Object> reset(@Valid Reset body) throws Exception { return Map.of("deletedRuns",service.resetHistory(body!=null&&body.confirm())); }
    @GET @Path("/runs") public List<Run> list() throws Exception { return service.list(); }
    @POST @Path("/runs") @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Run upload(@RestForm("current") FileUpload current,@RestForm("trailing") FileUpload trailing) throws Exception {
        if(current==null)throw new BadRequestException("Upload AvocadoBot 2.csv");
        filename(current);if(trailing!=null)filename(trailing);
        return service.create(Files.readAllBytes(current.uploadedFile()),trailing==null?null:Files.readAllBytes(trailing.uploadedFile()));
    }
    static void filename(FileUpload upload) { if(upload.fileName()==null||upload.fileName().contains("/")||upload.fileName().contains("\\")||!upload.fileName().toLowerCase(Locale.ROOT).endsWith(".csv"))throw new BadRequestException("Upload a CSV filename without a path"); }
    @Inject com.ibm.advocacy.service.WorkbookService workbooks;
    @POST @Path("/workbooks/inspect") @Consumes(MediaType.MULTIPART_FORM_DATA)
    public com.ibm.advocacy.service.WorkbookService.Inspection inspect(@RestForm("workbook") FileUpload workbook) throws Exception {
        workbookFilename(workbook);return workbooks.inspect(Files.readAllBytes(workbook.uploadedFile()));
    }
    @POST @Path("/runs/from-workbook") @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Run workbook(@RestForm("workbook") FileUpload workbook,@RestForm("month") String month,@RestForm("currentSheet") String currentSheet,@RestForm("historySheets") String historyJson) throws Exception {
        workbookFilename(workbook);
        List<String> history;
        try { history=historyJson==null?List.of():new com.fasterxml.jackson.databind.ObjectMapper().readValue(historyJson,new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}); }
        catch(Exception e) { throw new BadRequestException("Invalid history sheet selection"); }
        if(history==null||history.stream().anyMatch(Objects::isNull))throw new BadRequestException("Invalid history sheet selection");
        return service.createWorkbook(Files.readAllBytes(workbook.uploadedFile()),workbook.fileName(),month,currentSheet,history);
    }
    static void workbookFilename(FileUpload upload) {
        if(upload==null||upload.fileName()==null||upload.fileName().contains("/")||upload.fileName().contains("\\")||!upload.fileName().toLowerCase(Locale.ROOT).endsWith(".xlsx"))throw new BadRequestException("Upload an .xlsx workbook filename without a path");
    }
    @GET @Path("/runs/{id}/inputs/{name}/download") @Produces("text/csv")
    public Response input(@PathParam("id") String id,@PathParam("name") String name) throws Exception {
        Run run=service.get(id);String charset=!run.workbook.isEmpty()||name.equals("AvocadoBot 2.csv")?"utf-8":"windows-1252";
        return Response.ok(service.input(id,name),"text/csv; charset="+charset).header("Content-Disposition","attachment; filename=\""+name+"\"").header("X-Content-Type-Options","nosniff").build();
    }
    @POST @Path("/runs/from-runtimes") public Run seed() throws Exception { return service.seed(); }
    @GET @Path("/runs/{id}") public Map<String,Object> get(@PathParam("id") String id) throws Exception { Run run=service.get(id);return Map.of("run",run,"approvalDigest",RunService.digest(run)); }
    @POST @Path("/runs/{id}/start") public Response start(@PathParam("id") String id,@Valid Start body) throws Exception { return accepted(service.start(id,body!=null&&body.acceptContract())); }
    @POST @Path("/runs/{id}/phases/{phase}/approve") public Response approve(@PathParam("id") String id,@PathParam("phase") int phase,@Valid Approve body) throws Exception { if(body==null)throw new BadRequestException("Approval body required");return accepted(service.approve(id,phase,body.version(),body.digest(),body.acknowledgeIssues())); }
    @POST @Path("/runs/{id}/measurements") public Response measurement(@PathParam("id") String id,@Valid Manual body) throws Exception { if(body==null)throw new BadRequestException("Measurement body required");return accepted(service.measured(id,body.version(),body.rowId(),body.value(),body.type(),body.evidence())); }
@GET @Path("/runs/{id}/trailing-input") public RunService.TrailingInput trailingInput(@PathParam("id") String id,@QueryParam("rowId") String rowId) throws Exception {return service.trailingInput(id,rowId);}
@POST @Path("/runs/{id}/trailing-inputs") public Response reviewInput(@PathParam("id") String id,@Valid InputReview body) throws Exception {
    if(body==null)throw new BadRequestException("Input review body required");
    return accepted(service.reviewTrailingInput(id,body.version(),body.rowId(),body.views(),body.duration(),body.keepOriginal(),body.note()));
}
    @POST @Path("/runs/{id}/retry") public Response retry(@PathParam("id") String id) throws Exception { return accepted(service.retry(id)); }
    @POST @Path("/runs/{id}/cancel") public Run cancel(@PathParam("id") String id) throws Exception { return service.cancel(id); }
    @GET @Path("/runs/{id}/artifacts") public Collection<Artifact> artifacts(@PathParam("id") String id) throws Exception { return service.get(id).artifacts.values(); }
    @GET @Path("/runs/{id}/artifacts/{name}/preview") public Map<String,Object> preview(@PathParam("id") String id,@PathParam("name") String name,@QueryParam("version") Integer version,@QueryParam("offset") @DefaultValue("0") @Min(0) int offset,@QueryParam("limit") @DefaultValue("50") @Min(1) @Max(200) int limit) throws Exception {
        Data data=csv.read(service.artifact(id,name,version),false);int start=Math.min(offset,data.rows.size()),end=Math.min(start+limit,data.rows.size());return Map.of("headers",data.headers,"rows",data.rows.subList(start,end),"total",data.rows.size());
    }
    @GET @Path("/runs/{id}/artifacts/{name}/download") @Produces("text/csv") public Response download(@PathParam("id") String id,@PathParam("name") String name,@QueryParam("version") Integer version) throws Exception { return Response.ok(service.artifact(id,name,version),"text/csv; charset=utf-8").header("Content-Disposition","attachment; filename=\""+name+"\"").header("X-Content-Type-Options","nosniff").build(); }
    @GET @Path("/runs/{id}/download-all") @Produces("application/zip") public Response zip(@PathParam("id") String id) throws Exception { return Response.ok(service.zip(id)).header("Content-Disposition","attachment; filename=\"impact-metrics-"+id+".zip\"").build(); }
    @GET @Path("/runs/{id}/events") @Produces(MediaType.SERVER_SENT_EVENTS) @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<Map<String,Object>> events(@PathParam("id") String id,@QueryParam("after") @DefaultValue("0") long after) throws Exception {
        service.get(id);
        return Multi.createFrom().ticks().every(Duration.ofSeconds(1)).onItem().transformToUniAndConcatenate(tick->io.smallrye.mutiny.Uni.createFrom().item(()-> {
            try { Run run=service.get(id);return Map.<String,Object>of("version",run.version,"status",run.status,"phase",run.phase,"events",run.events.stream().filter(e->e.sequence()>after).toList()); }
            catch(NotFoundException deleted) { throw deleted; }
            catch(Exception e) { throw new RuntimeException(e); }
        }).runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool()))
            // Reset may remove a run while an SSE subscriber is still connected.
            // One terminal event tells the browser to stop reconnecting, then completes.
            .onFailure(NotFoundException.class).recoverWithItem(Map.<String,Object>of("status","RESET","runId",id,"events",List.of()));
    }
    static Response accepted(Run run) { return Response.accepted(run).build(); }
}
