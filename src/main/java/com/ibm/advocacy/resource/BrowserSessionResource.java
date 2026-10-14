package com.ibm.advocacy.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.ibm.advocacy.collector.LinkedInCollector;
import com.ibm.advocacy.collector.SharePointCollector;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

/** Protected by the Route's OAuth proxy; no session values are returned. */
@Path("/api/browser-sessions") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON) @Blocking
public class BrowserSessionResource {
    @Inject SharePointCollector sharepoint;
    @Inject LinkedInCollector linkedin;
    public record Session(String url,JsonNode state) {}
    @POST @Path("/{provider}") public Response save(@PathParam("provider") String provider,Session body) {
        if(body==null)throw new BadRequestException("Browser session required");
        Object result=switch(provider){case "sharepoint"->sharepoint.importSession(body.url(),body.state());case "linkedin"->linkedin.importSession(body.state());default->throw new NotFoundException();};
        return Response.accepted(result).header("Cache-Control","no-store").build();
    }
}
