package com.soapboxrace.core.api;

import com.soapboxrace.core.bo.AutoTuneBO;
import com.soapboxrace.core.bo.ParameterBO;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.QueryParam;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

@Path("/ReloadAutoTune")
public class ReloadAutoTune {

    @Inject
    private ParameterBO parameterBO;

    @Inject
    private AutoTuneBO autoTuneBO;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public String reloadAutoTune(@QueryParam("adminAuth") String token,
                                 @QueryParam("carName") String carName) {
        String adminToken = parameterBO.getStrParam("ADMIN_AUTH");

        if (adminToken == null) {
            return "ERROR! no admin token set in DB";
        }

        if (adminToken.equals(token)) {
            autoTuneBO.reloadStaticCaches();
            if (carName != null && !carName.trim().isEmpty()) {
                return autoTuneBO.reGenerateForCar(carName.trim());
            }
            return autoTuneBO.preGenerateAllSetups();
        } else {
            return "ERROR! invalid admin token";
        }
    }
}
