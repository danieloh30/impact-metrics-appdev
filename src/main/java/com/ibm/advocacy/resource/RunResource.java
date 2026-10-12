package com.ibm.advocacy.resource;

import com.ibm.advocacy.collector.LinkedInCollector;
import com.ibm.advocacy.model.Artifact;
import com.ibm.advocacy.model.Data;
import com.ibm.advocacy.model.Run;
import com.ibm.advocacy.service.Pipeline;
import com.ibm.advocacy.service.RunService;
import com.ibm.advocacy.service.TrailingReporting;

import io.smallrye.mutiny.Multi;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestStreamElementType;
import java.time.Duration;
import java.util.*;
import static com.ibm.advocacy.model.MetricsSchema.*;

@Path("/api") @Produces(MediaType.APPLICATION_JSON) @Blocking
public class RunResource {
    @Inject RunService service;
    @Inject LinkedInCollector linkedin;
    public record Start(boolean acceptContract) {}
    public record Reset(boolean confirm) {}
    public record Approve(@Min(1) int version,@NotBlank String digest,boolean acknowledgeIssues) {}
    public record InputReview(@Min(1) int version,@NotBlank String rowId,String views,String duration,boolean keepOriginal,@Size(max=1000) String note) {}
    public record Manual(@Min(1) int version,@NotBlank String rowId,@NotBlank String value,@NotBlank String type,@Size(max=1000) String evidence,boolean attributed) {}
    public record MonthEnd(@Min(0) int revision,@NotBlank String contribution,@NotBlank String rowId,@NotBlank String value,@NotBlank String asOf,@Size(max=1000) String note) {}
    public record ChartHistory(@Min(0) int revision,@NotBlank String url,String endMonth) {}
    @POST @Path("/runs/{id}/reporting/history") public Run chartHistory(@PathParam("id") String id,@Valid ChartHistory body) throws Exception {
        if(body==null)throw new BadRequestException("Chart history workbook required");return service.importChartHistory(id,body.revision(),body.url(),body.endMonth());
    }
    @GET @Path("/runs/{id}/reporting") public com.ibm.advocacy.service.MonthlyReporting.Report reporting(@PathParam("id") String id,@QueryParam("scope") @DefaultValue("combined") String scope) throws Exception {return service.report(id,scope);}
    @POST @Path("/runs/{id}/reporting/measurements") public Run monthEnd(@PathParam("id") String id,@Valid MonthEnd body) throws Exception {
        if(body==null)throw new BadRequestException("Month-end measurement required");return service.confirmMonthEnd(id,body.revision(),body.contribution(),body.rowId(),body.value(),body.asOf(),body.note());
    }
    @GET @Path("/runs/{id}/reporting/download") public Response reportingDownload(@PathParam("id") String id,@QueryParam("scope") @DefaultValue("combined") String scope,@QueryParam("format") @DefaultValue("xlsx") String format,@QueryParam("bob") @DefaultValue("false") boolean bob) throws Exception {
        if(!Set.of("xlsx","csv").contains(format))throw new BadRequestException("Unknown reporting format");
        String type=format.equals("xlsx")?"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":"text/csv; charset=utf-8";
        return Response.ok(service.reportingDownload(id,scope,format.equals("xlsx"),bob),type).header("Content-Disposition","attachment; filename=\"monthly-report-"+scope+"."+format+"\"").header("X-Content-Type-Options","nosniff").build();
    }
    @GET @Path("/config") public Map<String,Object> config() { return Map.of("phases",PHASES,"contract",Pipeline.CONTRACT,"outputs",OUTPUTS); }
    @GET @Path("/linkedin/session") public Map<String,Object> session() { return linkedin.session(); }
    @POST @Path("/linkedin/session") public Response connect() { return Response.accepted(linkedin.connect()).build(); }
    @POST @Path("/runs/reset") @Consumes(MediaType.APPLICATION_JSON)
    public Map<String,Object> reset(@Valid Reset body) throws Exception { return Map.of("deletedRuns",service.resetHistory(body!=null&&body.confirm())); }
    @GET @Path("/runs") public List<Run> list() throws Exception { return service.list(); }
    @GET @Path("/runs/{id}/inputs/{name}/download") @Produces("text/csv")
    public Response input(@PathParam("id") String id,@PathParam("name") String name) throws Exception {
        Run run=service.get(id);String charset=!run.workbook.isEmpty()||name.equals("AvocadoBot 2.csv")?"utf-8":"windows-1252";
        return Response.ok(service.input(id,name),"text/csv; charset="+charset).header("Content-Disposition","attachment; filename=\""+name+"\"").header("X-Content-Type-Options","nosniff").build();
    }
    @GET @Path("/runs/{id}") public Map<String,Object> get(@PathParam("id") String id) throws Exception { Run run=service.get(id);return Map.of("run",run,"approvalDigest",RunService.digest(run)); }
    @POST @Path("/runs/{id}/start") public Response start(@PathParam("id") String id,@Valid Start body) throws Exception { return accepted(service.start(id,body!=null&&body.acceptContract())); }
    @POST @Path("/runs/{id}/phases/{phase}/approve") public Response approve(@PathParam("id") String id,@PathParam("phase") int phase,@Valid Approve body) throws Exception { if(body==null)throw new BadRequestException("Approval body required");return accepted(service.approve(id,phase,body.version(),body.digest(),body.acknowledgeIssues())); }
    @GET @Path("/runs/{id}/measurement-input") public RunService.MeasurementInput measurementInput(@PathParam("id") String id,@QueryParam("rowId") String rowId,@QueryParam("type") String type) throws Exception {return service.measurementInput(id,rowId,type);}
    @POST @Path("/runs/{id}/measurements") public Response measurement(@PathParam("id") String id,@Valid Manual body) throws Exception { if(body==null)throw new BadRequestException("Measurement body required");return accepted(service.measured(id,body.version(),body.rowId(),body.value(),body.type(),body.evidence(),body.attributed())); }
@GET @Path("/runs/{id}/trailing-input") public RunService.TrailingInput trailingInput(@PathParam("id") String id,@QueryParam("rowId") String rowId) throws Exception {return service.trailingInput(id,rowId);}
@POST @Path("/runs/{id}/trailing-inputs") public Response reviewInput(@PathParam("id") String id,@Valid InputReview body) throws Exception {
    if(body==null)throw new BadRequestException("Input review body required");
    return accepted(service.reviewTrailingInput(id,body.version(),body.rowId(),body.views(),body.duration(),body.keepOriginal(),body.note()));
}
    @POST @Path("/runs/{id}/retry") public Response retry(@PathParam("id") String id) throws Exception { return accepted(service.retry(id)); }
    @POST @Path("/runs/{id}/cancel") public Run cancel(@PathParam("id") String id) throws Exception { return service.cancel(id); }
    @GET @Path("/runs/{id}/artifacts") public Collection<Artifact> artifacts(@PathParam("id") String id) throws Exception { return service.get(id).artifacts.values(); }
    @GET @Path("/runs/{id}/artifacts/{name}/preview") public Map<String,Object> preview(@PathParam("id") String id,@PathParam("name") String name,@QueryParam("version") Integer version,@QueryParam("offset") @DefaultValue("0") @Min(0) int offset,@QueryParam("limit") @DefaultValue("50") @Min(1) @Max(200) int limit,@QueryParam("q") @Size(max=200) String query,@QueryParam("basis") @DefaultValue("all") String basis) throws Exception {
        Data data=service.artifactData(id,name,version);
        String term=query==null?"":query.strip().toLowerCase(Locale.ROOT);
        if(!Set.of("all","growth","retained","unresolved").contains(basis))throw new BadRequestException("Unknown reporting basis");
        var matches=data.rows.stream().filter(row->basis.equals("all")||row.get("Growth classification").equals(basis))
            .filter(row->term.isEmpty()||data.headers.stream().anyMatch(header->row.get(header).toLowerCase(Locale.ROOT).contains(term))).toList();
        int start=Math.min(offset,matches.size()),end=Math.min(start+limit,matches.size());
        Map<String,Object> response=new LinkedHashMap<>(Map.of("headers",data.headers,"rows",matches.subList(start,end),"total",matches.size(),"unfilteredTotal",data.rows.size()));
        if(name.equals("updated_90-day.csv"))response.put("reporting",TrailingReporting.summary(data,service.get(id)));
        return response;
    }
    @GET @Path("/runs/{id}/artifacts/{name}/download") @Produces("text/csv") public Response download(@PathParam("id") String id,@PathParam("name") String name,@QueryParam("version") Integer version) throws Exception { return Response.ok(service.artifact(id,name,version),"text/csv; charset=utf-8").header("Content-Disposition","attachment; filename=\""+name+"\"").header("X-Content-Type-Options","nosniff").build(); }
    @GET @Path("/runs/{id}/download-all") @Produces("application/zip") public Response zip(@PathParam("id") String id) throws Exception { return Response.ok(service.zip(id)).header("Content-Disposition","attachment; filename=\"impact-metrics-"+id+".zip\"").build(); }
    @GET @Path("/runs/{id}/events") @Produces(MediaType.SERVER_SENT_EVENTS) @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<Map<String,Object>> events(@PathParam("id") String id,@QueryParam("after") @DefaultValue("0") long after) throws Exception {
        service.get(id);
        // Ticks are refresh triggers, not stored run events. Skip triggers while a
        // slow client/worker has no demand; the next snapshot includes persisted events.
        return Multi.createFrom().ticks().every(Duration.ofSeconds(1)).onOverflow().drop()
            .onItem().transformToUniAndConcatenate(tick->io.smallrye.mutiny.Uni.createFrom().item(()-> {
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
