package com.soapboxrace.core.api;

import javax.ws.rs.*;
import com.soapboxrace.core.api.util.Secured;

@Path("/ProgressionAmplifiers")
public class ProgressionAmplifiers {    
    @GET
    @Secured
    @Path("/Reapply")
    public String Reapply(@QueryParam("personaId") Long personaId, @HeaderParam("userId") Long userId) {
        return "";
    }
}