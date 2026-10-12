package com.ibm.advocacy.resource;

import com.ibm.advocacy.collector.SharePointCollector;
import com.ibm.advocacy.model.Run;
import com.ibm.advocacy.service.WorkbookSourceService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.jboss.resteasy.reactive.RestForm;
import io.smallrye.common.annotation.Blocking;
import java.util.*;

@Path("/api") @Produces(MediaType.APPLICATION_JSON) @Blocking
public class SharePointResource {
    @Inject SharePointCollector sharepoint;
    @Inject WorkbookSourceService sources;
    public record Link(@NotBlank String url) {}
    @GET @Path("/sharepoint/session") public Map<String,Object> session(){return sharepoint.session();}
    @POST @Path("/sharepoint/session") @Consumes(MediaType.APPLICATION_JSON)
    public Response connect(@Valid Link body){if(body==null)throw new BadRequestException("SharePoint workbook URL required");return Response.accepted(sharepoint.connect(body.url())).build();}
    @POST @Path("/workbooks/from-sharepoint") @Consumes(MediaType.APPLICATION_JSON)
    public WorkbookSourceService.SourceInspection inspect(@Valid Link body) throws Exception {if(body==null)throw new BadRequestException("SharePoint workbook URL required");return sources.inspect(body.url());}
    @POST @Path("/runs/from-sharepoint") @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Run create(@RestForm("sourceToken") String token,@RestForm("historySourceToken") String historyToken,@RestForm("month") String month,@RestForm("currentSheet") String current,@RestForm("chartHistoryId") String chartHistoryId) throws Exception {
        return sources.create(token,historyToken,month,current,chartHistoryId);
    }
}
