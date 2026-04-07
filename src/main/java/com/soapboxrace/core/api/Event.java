/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.api;

import com.soapboxrace.core.api.util.Secured;
import com.soapboxrace.core.bo.*;
import com.soapboxrace.core.bo.util.RacerStatus;
import com.soapboxrace.core.dao.*;
import com.soapboxrace.core.jpa.*;
import com.soapboxrace.core.xmpp.OpenFireSoapBoxCli;
import com.soapboxrace.core.xmpp.XmppChat;
import com.soapboxrace.jaxb.http.*;
import com.soapboxrace.jaxb.util.JAXBUtility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.io.InputStream;
import java.time.LocalDateTime;

@Path("/event")
public class Event {

    private static final Logger logger = LoggerFactory.getLogger(Event.class);

    @Inject
    private TokenSessionBO tokenBO;

    @Inject
    private EventBO eventBO;

    @Inject
    private EventResultDragBO eventResultDragBO;

    @Inject
    private EventResultPursuitBO eventResultPursuitBO;

    @Inject
    private EventResultRouteBO eventResultRouteBO;

    @Inject
    private EventResultTeamEscapeBO eventResultTeamEscapeBO;

    @Inject
    private MatchmakingBO matchmakingBO;

    @Inject
    private ParameterBO parameterBO;

    @Inject
    private EventSessionDAO eventSessionDao;

    @Inject
    private LobbyEntrantDAO lobbyEntrantDao;

    @Inject
    private EventDataDAO eventDataDAO;

    @Inject
    private PersonaDAO personaDAO;

    @Inject
    private PresenceBO presenceBO;

    @Inject
    private RequestSessionInfo requestSessionInfo;

    @Inject
    private OpenFireSoapBoxCli openFireSoapBoxCli;

    @POST
    @Secured
    @Path("/abort")
    @Produces(MediaType.APPLICATION_XML)
    public String abort(InputStream inputStream, @QueryParam("eventSessionId") Long eventSessionId) {
        Long activePersonaId = requestSessionInfo.getActivePersonaId();
        
        // Appeler la méthode arbitration si un inputStream est fourni
        String arbitrationRedirect = "";
        if (inputStream != null) {
            arbitrationRedirect = arbitration(inputStream, eventSessionId);
        }

        // Gestion des pénalités de quitter la course
        EventDataEntity leavepenality = eventDataDAO.findByPersonaAndEventSessionId(activePersonaId, eventSessionId);
        if(leavepenality != null) {
            leavepenality.setLeftRace(true);
            leavepenality.setRacerStatus(RacerStatus.ABANDONED);

            eventDataDAO.update(leavepenality);
        }
        
        // Nettoyage des sessions
        tokenBO.setEventSessionId(requestSessionInfo.getTokenSessionEntity(), null);
        tokenBO.setActiveLobbyId(requestSessionInfo.getTokenSessionEntity(), null);
        
        // Retirer le joueur des files d'attente pour éviter qu'un lobby Race Again lui envoie une invitation
        matchmakingBO.removePlayerFromQueue(activePersonaId);
        matchmakingBO.removePlayerFromRaceNowQueue(activePersonaId);

        // Remettre le statut de présence à "en ligne" (1) après abandon de course
        if (activePersonaId != null && !activePersonaId.equals(0L)) {
            presenceBO.setPresenceOnline(activePersonaId);
        }
        
        return arbitrationRedirect.isEmpty() ? "" : arbitrationRedirect;
    }

    @PUT
    @Secured
    @Path("/launched")
    @Produces(MediaType.APPLICATION_XML)
    public String launched(@QueryParam("eventSessionId") Long eventSessionId) {
        Long activePersonaId = requestSessionInfo.getActivePersonaId();
        EventSessionEntity eventSessionEntity = eventSessionDao.find(eventSessionId);
        Long sessionStarted = eventSessionEntity != null ? eventSessionEntity.getStarted() : null;
        long delayMs = (sessionStarted != null) ? (System.currentTimeMillis() - sessionStarted) : -1L;

        logger.info("EVENT_LAUNCHED: PersonaId={} called /Event/launched for EventSessionId={} (DelaySinceSessionStart={}ms)", 
            activePersonaId, eventSessionId, delayMs);
        
        matchmakingBO.removePlayerFromQueue(activePersonaId);
        matchmakingBO.removePlayerFromRaceNowQueue(activePersonaId);
        
        logger.info("EVENT_LAUNCHED: Creating EventData for PersonaId={} in EventSessionId={}", 
            activePersonaId, eventSessionId);
        eventBO.createEventDataSession(activePersonaId, eventSessionId);
        logger.info("EVENT_LAUNCHED: EventData created successfully for PersonaId={}", activePersonaId);
        
        tokenBO.setEventSessionId(requestSessionInfo.getTokenSessionEntity(), eventSessionId);
        requestSessionInfo.getTokenSessionEntity().setInSafehouse(false);

        // Mettre le statut de présence à "en course" (-1)
        if (activePersonaId != null && !activePersonaId.equals(0L)) {
            presenceBO.setPresenceInRace(activePersonaId);
        }

        //NOPU SETH
        if(parameterBO.getBoolParam("SBRWR_ENABLE_NOPU")) {
            Boolean nopuMode = false;

            LobbyEntity lobbyEntities = eventSessionEntity != null ? eventSessionEntity.getLobby() : null;

            if(lobbyEntities != null) {
                List<LobbyEntrantEntity> lobbyEntrants = lobbyEntities.getEntrants();

                Integer totalVotes = lobbyEntrantDao.getVotes(lobbyEntities);
                Integer totalUsersInLobby = lobbyEntrants.size();
                Integer totalVotesPercentage = Math.round((totalVotes * 100.0f) / totalUsersInLobby);
                    
                if(totalVotesPercentage >= parameterBO.getIntParam("SBRWR_NOPU_REQUIREDPERCENT")) {
                    nopuMode = true;
                }
            }

            if (eventSessionEntity != null) {
                eventSessionEntity.setNopuMode(nopuMode);
                eventSessionDao.update(eventSessionEntity);
            }
        }

        return "";
    }

    @POST
    @Secured
    @Path("/arbitration")
    @Produces(MediaType.APPLICATION_XML)
    public String arbitration(InputStream arbitrationXml,
                              @QueryParam("eventSessionId") Long eventSessionId) {
        EventSessionEntity eventSessionEntity = eventBO.findEventSessionById(eventSessionId);
        EventEntity event = eventSessionEntity.getEvent();
        EventMode eventMode = EventMode.fromId(event.getEventModeId());
        Long activePersonaId = requestSessionInfo.getActivePersonaId();
        EventResult eventResult = null;

        switch (eventMode) {
            case CIRCUIT:
            case SPRINT:
                RouteArbitrationPacket routeArbitrationPacket = JAXBUtility.unMarshal(arbitrationXml, RouteArbitrationPacket.class);
                eventResult = eventResultRouteBO.handle(eventSessionEntity, activePersonaId, routeArbitrationPacket);
                break;
            case DRAG:
                DragArbitrationPacket dragArbitrationPacket = JAXBUtility.unMarshal(arbitrationXml, DragArbitrationPacket.class);
                eventResult = eventResultDragBO.handle(eventSessionEntity, activePersonaId, dragArbitrationPacket);
                break;
            case PURSUIT_MP:
                TeamEscapeArbitrationPacket teamEscapeArbitrationPacket = JAXBUtility.unMarshal(arbitrationXml, TeamEscapeArbitrationPacket.class);
                eventResult = eventResultTeamEscapeBO.handle(eventSessionEntity, activePersonaId, teamEscapeArbitrationPacket);
                break;
            case PURSUIT_SP:
                PursuitArbitrationPacket pursuitArbitrationPacket = JAXBUtility.unMarshal(arbitrationXml, PursuitArbitrationPacket.class);
                eventResult = eventResultPursuitBO.handle(eventSessionEntity, activePersonaId, pursuitArbitrationPacket);
                break;
            case MEETINGPLACE:
            default:
                break;
        }

        tokenBO.setEventSessionId(requestSessionInfo.getTokenSessionEntity(), null);
        tokenBO.setActiveLobbyId(requestSessionInfo.getTokenSessionEntity(), null);

        // Remettre le statut de présence à "en ligne" (1) après la course
        if (activePersonaId != null && !activePersonaId.equals(0L)) {
            presenceBO.setPresenceOnline(activePersonaId);
        }

        if (eventResult == null) {
            return "";
        }

        return JAXBUtility.marshal(eventResult);
    }

    @POST
    @Secured
    @Path("/bust")
    @Produces(MediaType.APPLICATION_XML)
    public String bust(InputStream bustXml, @HeaderParam("securityToken") String securityToken, @QueryParam("eventSessionId") Long eventSessionId) {
        EventSessionEntity eventSessionEntity = eventBO.findEventSessionById(eventSessionId);
        EventDataEntity bustedData = eventDataDAO.findByPersonaAndEventSessionId(requestSessionInfo.getActivePersonaId() ,eventSessionId);

        if(bustedData != null) {
            bustedData.setRacerStatus(RacerStatus.BUSTED);
            eventDataDAO.update(bustedData);
        }

        PursuitArbitrationPacket pursuitArbitrationPacket = JAXBUtility.unMarshal(bustXml, PursuitArbitrationPacket.class);
        Long activePersonaId = requestSessionInfo.getActivePersonaId();
        
        // Remettre le statut de présence à "en ligne" (1) après la poursuite
        if (activePersonaId != null && !activePersonaId.equals(0L)) {
            presenceBO.setPresenceOnline(activePersonaId);
        }
        
        return JAXBUtility.marshal(eventResultPursuitBO.handle(eventSessionEntity, activePersonaId, pursuitArbitrationPacket));
    }
}
