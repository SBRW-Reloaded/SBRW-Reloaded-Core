/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.api;

import com.soapboxrace.core.api.util.Secured;
import com.soapboxrace.core.bo.*;
import com.soapboxrace.core.dao.*;
import com.soapboxrace.core.jpa.*;
import com.soapboxrace.core.xmpp.OpenFireSoapBoxCli;
import com.soapboxrace.core.xmpp.XmppChat;
import com.soapboxrace.jaxb.http.*;
import com.soapboxrace.jaxb.util.JAXBUtility;
import java.util.List;

import javax.ejb.EJB;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.io.InputStream;

@Path("/event")
public class Event {

    @EJB
    private TokenSessionBO tokenBO;

    @EJB
    private EventBO eventBO;

    @EJB
    private EventResultDragBO eventResultDragBO;

    @EJB
    private EventResultPursuitBO eventResultPursuitBO;

    @EJB
    private EventResultRouteBO eventResultRouteBO;

    @EJB
    private EventResultTeamEscapeBO eventResultTeamEscapeBO;

    @EJB
    private MatchmakingBO matchmakingBO;

    @EJB
    private ParameterBO parameterBO;

    @EJB
    private EventSessionDAO eventSessionDao;

    @EJB
    private LobbyEntrantDAO lobbyEntrantDao;

    @EJB
    private EventDataDAO eventDataDAO;

    @EJB
    private PersonaDAO personaDAO;
    
    @EJB
    private PresenceBO presenceBO;

    @Inject
    private RequestSessionInfo requestSessionInfo;

    @EJB
    private OpenFireSoapBoxCli openFireSoapBoxCli;

    @POST
    @Secured
    @Path("/abort")
    @Produces(MediaType.APPLICATION_XML)
    public String abort(@QueryParam("eventSessionId") Long eventSessionId) {

        EventDataEntity leavepenality = eventDataDAO.findByPersonaAndEventSessionId(requestSessionInfo.getActivePersonaId() ,eventSessionId);
        leavepenality.setRank(-1);
        leavepenality.setFinishReason(518);
        eventDataDAO.update(leavepenality);

        if(leavepenality.getEvent().isRankedMode()) {
            PersonaEntity personaEntity = personaDAO.find(requestSessionInfo.getActivePersonaId());
            int current_ranking_points = personaEntity.getRankingPoints();
            int ranking_points_earned = parameterBO.getIntParam("SBRWR_RANKEDMODE_POINTS_LEFTRACE", -28);

            int calculated_ranking_points = Math.max(current_ranking_points + ranking_points_earned, 0);
            if(calculated_ranking_points == 0) ranking_points_earned = 0;

            String rankingMessage = String.format("SBRWR_RANKEDMODE_POS_LEFT,%s,%s", ranking_points_earned, calculated_ranking_points);
            openFireSoapBoxCli.send(XmppChat.createSystemMessage(rankingMessage), requestSessionInfo.getActivePersonaId());
        }

        tokenBO.setEventSessionId(requestSessionInfo.getTokenSessionEntity(), null);
        tokenBO.setActiveLobbyId(requestSessionInfo.getTokenSessionEntity(), null);
        return "";
    }

    @PUT
    @Secured
    @Path("/launched")
    @Produces(MediaType.APPLICATION_XML)
    public String launched(@QueryParam("eventSessionId") Long eventSessionId) {
        Long activePersonaId = requestSessionInfo.getActivePersonaId();
        matchmakingBO.removePlayerFromQueue(activePersonaId);
        eventBO.createEventDataSession(activePersonaId, eventSessionId);
        tokenBO.setEventSessionId(requestSessionInfo.getTokenSessionEntity(), eventSessionId);

        //NOPU SETH
        if(parameterBO.getBoolParam("SBRWR_ENABLE_NOPU")) {
            Boolean nopuMode = false;

            EventSessionEntity eventSessionEntity = eventSessionDao.find(eventSessionId);
            LobbyEntity lobbyEntities = eventSessionEntity.getLobby();

            if(lobbyEntities != null) {
                List<LobbyEntrantEntity> lobbyEntrants = lobbyEntities.getEntrants();

                Integer totalVotes = lobbyEntrantDao.getVotes(lobbyEntities);
                Integer totalUsersInLobby = lobbyEntrants.size();
                Integer totalVotesPercentage = Math.round((totalVotes * 100.0f) / totalUsersInLobby);
                    
                if(totalVotesPercentage >= parameterBO.getIntParam("SBRWR_NOPU_REQUIREDPERCENT")) {
                    nopuMode = true;
                }
            }

            eventSessionEntity.setNopuMode(nopuMode);
            eventSessionDao.update(eventSessionEntity);
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
                RouteArbitrationPacket routeArbitrationPacket = JAXBUtility.unMarshal(arbitrationXml,
                        RouteArbitrationPacket.class);
                eventResult = eventResultRouteBO.handle(eventSessionEntity, activePersonaId,
                        routeArbitrationPacket);
                break;
            case DRAG:
                DragArbitrationPacket dragArbitrationPacket = JAXBUtility.unMarshal(arbitrationXml,
                        DragArbitrationPacket.class);
                eventResult = eventResultDragBO.handle(eventSessionEntity, activePersonaId, dragArbitrationPacket);
                break;
            case PURSUIT_MP:
                TeamEscapeArbitrationPacket teamEscapeArbitrationPacket = JAXBUtility.unMarshal(arbitrationXml,
                        TeamEscapeArbitrationPacket.class);
                eventResult = eventResultTeamEscapeBO.handle(eventSessionEntity, activePersonaId,
                        teamEscapeArbitrationPacket);
                break;
            case PURSUIT_SP:
                PursuitArbitrationPacket pursuitArbitrationPacket = JAXBUtility.unMarshal(arbitrationXml,
                        PursuitArbitrationPacket.class);
                eventResult = eventResultPursuitBO.handle(eventSessionEntity, activePersonaId,
                        pursuitArbitrationPacket);
                break;
            case MEETINGPLACE:
            default:
                break;
        }

        tokenBO.setEventSessionId(requestSessionInfo.getTokenSessionEntity(), null);
        tokenBO.setActiveLobbyId(requestSessionInfo.getTokenSessionEntity(), null);

        if (eventResult == null) {
            return "";
        }

        return JAXBUtility.marshal(eventResult);
    }

    @POST
    @Secured
    @Path("/bust")
    @Produces(MediaType.APPLICATION_XML)
    public String bust(InputStream bustXml, @HeaderParam("securityToken") String securityToken, @QueryParam(
            "eventSessionId") Long eventSessionId) {
        EventSessionEntity eventSessionEntity = eventBO.findEventSessionById(eventSessionId);
        PursuitArbitrationPacket pursuitArbitrationPacket = JAXBUtility.unMarshal(bustXml,
                PursuitArbitrationPacket.class);
        Long activePersonaId = requestSessionInfo.getActivePersonaId();
        return JAXBUtility.marshal(eventResultPursuitBO.handle(eventSessionEntity, activePersonaId, pursuitArbitrationPacket));
    }
}
