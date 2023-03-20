/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.api;

import com.soapboxrace.core.api.util.Secured;
import com.soapboxrace.core.bo.PresenceBO;
import com.soapboxrace.core.bo.RequestSessionInfo;

import javax.ejb.EJB;
import javax.inject.Inject;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.Objects;

@Path("/heartbeat")
public class HeartBeat {

    @EJB
    private PresenceBO presenceBO;

    @Inject
    private RequestSessionInfo requestSessionInfo;

    @POST
    @Secured
    @Produces(MediaType.APPLICATION_XML)
    public com.soapboxrace.jaxb.http.HeartBeat heartbeat() {
        Long activePersonaId = requestSessionInfo.getActivePersonaId();
        if (!Objects.isNull(activePersonaId) && !activePersonaId.equals(0L)) {
            presenceBO.refreshPresence(activePersonaId);
        }

        com.soapboxrace.jaxb.http.HeartBeat heartBeat = new com.soapboxrace.jaxb.http.HeartBeat();
        heartBeat.setEnabledBitField(0);
        heartBeat.setMetagameFlags(2);
        return heartBeat;
    }
}
