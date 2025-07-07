/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.api;

import com.soapboxrace.core.bo.PresenceBO;
import org.slf4j.Logger;

import javax.ejb.EJB;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * Endpoint d'administration pour diagnostiquer les problèmes de présence.
 * Utilisé pour surveiller et déboguer le système de présence des amis.
 */
@Path("/admin/presence")
public class PresenceAdmin {

    @EJB
    private PresenceBO presenceBO;

    @Inject
    private Logger logger;

    /**
     * Retourne des informations détaillées sur l'état du système de présence
     * pour aider au debugging des problèmes de liste d'amis
     */
    @GET
    @Path("/debug")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getPresenceDebugInfo() {
        try {
            String debugInfo = presenceBO.getPresenceDebugInfo();
            logger.info("Presence debug info requested");
            return Response.ok(debugInfo).build();
        } catch (Exception e) {
            logger.error("Error retrieving presence debug info", e);
            return Response.serverError()
                    .entity("Error retrieving presence debug info: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Force un nettoyage manuel des présences incohérentes
     */
    @GET
    @Path("/cleanup")
    @Produces(MediaType.TEXT_PLAIN)
    public Response forceCleanup() {
        try {
            presenceBO.cleanupPresenceInconsistencies();
            logger.info("Manual presence cleanup executed");
            return Response.ok("Presence cleanup executed successfully").build();
        } catch (Exception e) {
            logger.error("Error during manual presence cleanup", e);
            return Response.serverError()
                    .entity("Error during cleanup: " + e.getMessage())
                    .build();
        }
    }
}
