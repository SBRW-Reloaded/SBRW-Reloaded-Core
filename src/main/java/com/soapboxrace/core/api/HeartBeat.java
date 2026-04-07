package com.soapboxrace.core.api;

import com.soapboxrace.core.api.util.Secured;
import com.soapboxrace.core.bo.PresenceBO;
import com.soapboxrace.core.bo.RequestSessionInfo;
import com.soapboxrace.core.bo.TokenSessionBO;

import javax.inject.Inject;
import javax.inject.Inject;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.Objects;

@Path("/heartbeat")
public class HeartBeat {

    @Inject
    private PresenceBO presenceBO;

    @Inject
    private RequestSessionInfo requestSessionInfo;

    @Inject
    private TokenSessionBO tokenSessionBO;

    @POST
    @Secured
    @Produces(MediaType.APPLICATION_XML)
    public com.soapboxrace.jaxb.http.HeartBeat heartbeat() {
        // Record the heartbeat timestamp for session expiration tracking
        String securityToken = requestSessionInfo.getSecurityToken();
        if (securityToken != null) {
            tokenSessionBO.recordHeartbeat(securityToken);
        }

        Long activePersonaId = requestSessionInfo.getActivePersonaId();
        if (!Objects.isNull(activePersonaId) && !activePersonaId.equals(0L)) {
            // Utiliser la méthode sécurisée qui ne recrée pas de présence
            presenceBO.refreshPresenceIfExists(activePersonaId);
        }

        com.soapboxrace.jaxb.http.HeartBeat heartBeat = new com.soapboxrace.jaxb.http.HeartBeat();
        heartBeat.setEnabledBitField(0);
        heartBeat.setMetagameFlags(2);
        return heartBeat;
    }
}
