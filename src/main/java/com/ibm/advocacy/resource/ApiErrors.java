package com.ibm.advocacy.resource;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.*;
import java.util.Map;

@Provider
public class ApiErrors implements ExceptionMapper<Exception> {
    @Override public Response toResponse(Exception e) {
        int status=e instanceof WebApplicationException web?web.getResponse().getStatus():e instanceof IllegalArgumentException?400:500;
        String message=status==500?"Operation failed. Check the run status and server log.":e.getMessage();
        if(status==500)org.jboss.logging.Logger.getLogger(ApiErrors.class).error("Metrics API failure",e);
        return Response.status(status).entity(Map.of("error",message==null?"Request failed":message)).type("application/json").build();
    }
}
