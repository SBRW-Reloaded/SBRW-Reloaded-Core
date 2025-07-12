/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.api;

import com.soapboxrace.core.api.util.Secured;
import com.soapboxrace.core.bo.*;
import com.soapboxrace.core.jpa.EventSessionEntity;
import com.soapboxrace.core.jpa.PersonaEntity;
import com.soapboxrace.core.jpa.EventEntity;
import com.soapboxrace.core.jpa.LobbyEntity;
import com.soapboxrace.core.jpa.TokenSessionEntity;
import com.soapboxrace.jaxb.http.LobbyInfo;
import com.soapboxrace.jaxb.http.OwnedCarTrans;
import com.soapboxrace.jaxb.http.SecurityChallenge;
import com.soapboxrace.jaxb.http.SessionInfo;
import com.soapboxrace.core.xmpp.OpenFireSoapBoxCli;
import com.soapboxrace.core.xmpp.XmppChat;

import javax.ejb.EJB;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

import com.soapboxrace.core.bo.util.DiscordWebhook;
import com.soapboxrace.core.dao.PersonaDAO;
import com.soapboxrace.core.dao.LobbyDAO;
import com.soapboxrace.core.xmpp.OpenFireRestApiCli;
import org.slf4j.Logger;

@Path("/matchmaking")
public class MatchMaking {

    @EJB
    private EventBO eventBO;

    @EJB
    private LobbyBO lobbyBO;

    @EJB
    private TokenSessionBO tokenSessionBO;

    @EJB
    private PersonaBO personaBO;

    @EJB
    private MatchmakingBO matchmakingBO;

    @EJB
    private DiscordWebhook discordWebhook;
    
    @EJB
    private LobbyDAO lobbyDAO;

    @EJB
    private PersonaDAO personaDAO;

    @EJB
    private ParameterBO parameterBO;

    @EJB
    private OpenFireRestApiCli openFireRestApiCli;

    @EJB
    private OpenFireSoapBoxCli openFireSoapBoxCli;

    @Inject
    private RequestSessionInfo requestSessionInfo;

    @Inject
    private Logger logger;

    @PUT
    @Secured
    @Path("/joinqueueracenow")
    @Produces(MediaType.APPLICATION_XML)
    public String joinQueueRaceNow() {
        Long activePersonaId = requestSessionInfo.getActivePersonaId();
        logger.info("RACENOW ENDPOINT: PersonaId={} is requesting to join RaceNow queue", activePersonaId);
        
        OwnedCarTrans defaultCar = personaBO.getDefaultCar(activePersonaId);
        int carClassHash = defaultCar.getCustomCar().getCarClassHash();
        
        logger.info("RACENOW ENDPOINT: PersonaId={} using car with class hash={}", activePersonaId, carClassHash);
        
        lobbyBO.joinFastLobby(activePersonaId, carClassHash);
        
        logger.info("RACENOW ENDPOINT: PersonaId={} joinFastLobby call completed", activePersonaId);
        return "";
    }

    @PUT
    @Secured
    @Path("/joinqueueevent/{eventId}")
    @Produces(MediaType.APPLICATION_XML)
    public String joinQueueEvent(@PathParam("eventId") int eventId) {
        lobbyBO.joinQueueEvent(requestSessionInfo.getActivePersonaId(), eventId);
        return "";
    }

    @PUT
    @Secured
    @Path("/leavequeue")
    @Produces(MediaType.APPLICATION_XML)
    public String leaveQueue() {
        Long activePersonaId = requestSessionInfo.getActivePersonaId();
        logger.info("LEAVE_QUEUE ENDPOINT: PersonaId={} is leaving all queues", activePersonaId);
        
        matchmakingBO.removePlayerFromQueue(activePersonaId);
        // Retirer aussi de la file RaceNow persistante
        matchmakingBO.removePlayerFromRaceNowQueue(activePersonaId);
        
        logger.info("LEAVE_QUEUE ENDPOINT: PersonaId={} removed from all queues", activePersonaId);
        
        tokenSessionBO.setEventSessionId(requestSessionInfo.getTokenSessionEntity(), null);
        tokenSessionBO.setActiveLobbyId(requestSessionInfo.getTokenSessionEntity(), null);
        return "";
    }

    @PUT
    @Secured
    @Path("/leavelobby")
    @Produces(MediaType.APPLICATION_XML)
    public String leavelobby() {
        Long activePersonaId = requestSessionInfo.getActivePersonaId();
        Long activeLobbyId = requestSessionInfo.getActiveLobbyId();
        if (activeLobbyId != null && !activeLobbyId.equals(0L)) {
            lobbyBO.removeEntrantFromLobby(activePersonaId, activeLobbyId);
            tokenSessionBO.setEventSessionId(requestSessionInfo.getTokenSessionEntity(), null);
            tokenSessionBO.setActiveLobbyId(requestSessionInfo.getTokenSessionEntity(), null);
        }
        return "";
    }

    @GET
    @Secured
    @Path("/launchevent/{eventId}")
    @Produces(MediaType.APPLICATION_XML)
    public SessionInfo launchEvent(@PathParam("eventId") int eventId) {
        TokenSessionEntity tokenSessionEntity = requestSessionInfo.getTokenSessionEntity();
        EventSessionEntity createEventSession = eventBO.createEventSession(tokenSessionEntity, eventId);

        SessionInfo sessionInfo = new SessionInfo();
        SecurityChallenge securityChallenge = new SecurityChallenge();
        securityChallenge.setChallengeId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        securityChallenge.setLeftSize(14);
        securityChallenge.setPattern("FFFFFFFFFFFFFFFF");
        securityChallenge.setRightSize(50);
        sessionInfo.setChallenge(securityChallenge);
        sessionInfo.setEventId(eventId);
        sessionInfo.setSessionId(createEventSession.getId());
        tokenSessionBO.setActiveLobbyId(tokenSessionEntity, 0L);
        return sessionInfo;
    }

    @PUT
    @Secured
    @Path("/makeprivatelobby/{eventId}")
    @Produces(MediaType.APPLICATION_XML)
    public String makePrivateLobby(@PathParam("eventId") int eventId) {
        lobbyBO.createPrivateLobby(requestSessionInfo.getActivePersonaId(), eventId);
        return "";
    }

    @PUT
    @Secured
    @Path("/acceptinvite")
    @Produces(MediaType.APPLICATION_XML)
    public LobbyInfo acceptInvite(@QueryParam("lobbyInviteId") Long lobbyInviteId) {
        tokenSessionBO.setActiveLobbyId(requestSessionInfo.getTokenSessionEntity(), lobbyInviteId);
        Long activePersonaId = requestSessionInfo.getActivePersonaId();

        LobbyEntity lobbyInformation = lobbyDAO.findById(lobbyInviteId);

		if(activePersonaId.equals(lobbyInformation.getPersonaId()) && lobbyInformation.getIsPrivate() == false) {
			EventEntity eventInformation = lobbyInformation.getEvent();
			String eventNameFull = eventInformation.getName();
			String eventName = eventNameFull.split("\\(")[0];

			PersonaEntity personaEntity = personaDAO.find(activePersonaId);
			
			String msg = "[" + personaEntity.getName() + "] is looking for racers on " + eventName;
			String msgDs = "**" + personaEntity.getName() + "** is looking for racers on **" + eventName + "**";

			if(parameterBO.getStrParam("DISCORD_WEBHOOK_LOBBY_URL") != null) {
				discordWebhook.sendMessage(msgDs, 
					parameterBO.getStrParam("DISCORD_WEBHOOK_LOBBY_URL"), 
					parameterBO.getStrParam("DISCORD_WEBHOOK_LOBBY_NAME", "[SBRW] Server"),
					0xbb00ff
				);
			}

            if(parameterBO.getBoolParam("SBRWR_INFORM_EVENT") == true && parameterBO.getIntParam("SBRWR_INFORM_EVENT_USERCOUNT", 30) >= openFireRestApiCli.getTotalOnlineUsers()) {
                openFireRestApiCli.sendChatAnnouncement(msg);
            } 
        }

        if(parameterBO.getBoolParam("SBRWR_ENABLE_NOPU") && (lobbyInformation.getEvent().getEventModeId() != 19 || lobbyInformation.getEvent().getEventModeId() != 22)) {
            openFireSoapBoxCli.send(XmppChat.createSystemMessage("SBRWR_NOPU_JOIN_MSG," + parameterBO.getStrParam("SBRWR_NOPU_REQUIREDPERCENT")), activePersonaId);
        }

        return lobbyBO.acceptinvite(requestSessionInfo.getActivePersonaId(), lobbyInviteId);
    }

    @PUT
    @Secured
    @Path("/declineinvite")
    @Produces(MediaType.APPLICATION_XML)
    public String declineInvite(@QueryParam("lobbyInviteId") Long lobbyInviteId) {
        Long activePersonaId = requestSessionInfo.getActivePersonaId();
        logger.info("DECLINE_INVITE ENDPOINT: PersonaId={} is declining lobbyInviteId={}", activePersonaId, lobbyInviteId);
        
        lobbyBO.declineinvite(activePersonaId, lobbyInviteId);
        
        logger.info("DECLINE_INVITE ENDPOINT: PersonaId={} decline completed", activePersonaId);
        return "";
    }

}
