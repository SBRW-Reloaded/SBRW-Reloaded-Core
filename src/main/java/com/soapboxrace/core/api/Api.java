package com.soapboxrace.core.api;

import com.soapboxrace.core.bo.*;
import com.soapboxrace.core.bo.util.OwnedCarConverter;
import com.soapboxrace.core.dao.CarDAO;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
 
@Path("/api")
public class Api {

    @Inject
    private ParameterBO parameterBO;

    @Inject
    private CarDAO carDAO;

    @GET
    @Path("/getCarByID")
    @Produces(MediaType.APPLICATION_XML)
    public Response getCarByID(@QueryParam("carId") Long carID, @QueryParam("adminAuth") String token) {
        String adminToken = parameterBO.getStrParam("ADMIN_AUTH");

        if (adminToken == null) {
            return Response.ok().entity("<error>Missing ADMIN_AUTH</error>").build();
        }

        if (!adminToken.equals(token)) {
            return Response.ok().entity("<error>Invalid ADMIN_AUTH</error>").build();
        }

        if(carID == null) {
            return Response.ok().entity("<error>Missing carId param</error>").build();
        }
        
        return Response.ok().entity(OwnedCarConverter.entity2Trans(carDAO.find(carID))).build();
    }
}
 