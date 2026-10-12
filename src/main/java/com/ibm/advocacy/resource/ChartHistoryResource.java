package com.ibm.advocacy.resource;

import com.ibm.advocacy.storage.ChartHistoryStore;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.Map;

@Path("/api/chart-history") @Produces(MediaType.APPLICATION_JSON) @Blocking
public class ChartHistoryResource {
    @Inject ChartHistoryStore store;
    @ConfigProperty(name="metrics.reporting.chart-history-url") String defaultUrl;
    public record Request(@NotBlank String url,String endMonth) {}
    @GET public Object cached(@QueryParam("url") String url) throws Exception {
        var saved=store.cached(url==null||url.isBlank()?defaultUrl:url);return saved==null?Map.of("source",Map.of()):saved;
    }
    @POST @Consumes(MediaType.APPLICATION_JSON) public ChartHistoryStore.Saved read(@Valid Request body) throws Exception {
        if(body==null)throw new BadRequestException("Chart history workbook URL required");return store.read(body.url(),body.endMonth());
    }
}
