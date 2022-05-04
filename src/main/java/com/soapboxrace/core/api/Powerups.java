/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.api;

import com.soapboxrace.core.api.util.Secured;
import com.soapboxrace.core.bo.*;
import com.soapboxrace.core.bo.util.AchievementInventoryContext;
import com.soapboxrace.core.jpa.EventSessionEntity;
import com.soapboxrace.core.jpa.InventoryItemEntity;
import com.soapboxrace.core.jpa.PersonaEntity;
import com.soapboxrace.core.xmpp.OpenFireSoapBoxCli;
import com.soapboxrace.jaxb.xmpp.XMPP_PowerupActivatedType;
import com.soapboxrace.jaxb.xmpp.XMPP_ResponseTypePowerupActivated;

import com.soapboxrace.core.xmpp.XmppChat;

import javax.ejb.EJB;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.Map;

@Path("/powerups")
public class Powerups {

    @EJB
    private InventoryBO inventoryBO;

    @EJB
    private OpenFireSoapBoxCli openFireSoapBoxCli;

    @EJB
    private ParameterBO parameterBO;

    @EJB
    private PersonaBO personaBO;

    @EJB
    private AchievementBO achievementBO;

    @EJB
    private PowerupTrackingBO usedPowerupBO;

    @EJB
    private EventBO eventBO;

    @Inject
    private RequestSessionInfo requestSessionInfo;

    @POST
    @Secured
    @Path("/activated/{powerupHash}")
    @Produces(MediaType.APPLICATION_XML)
    public String activated(@PathParam(value = "powerupHash") Integer powerupHash, @QueryParam("targetId") Long targetId, @QueryParam("receivers") String receivers, @QueryParam("eventSessionId") Integer eventSessionId) {
        Long activePersonaId = requestSessionInfo.getActivePersonaId();

        if(requestSessionInfo.getEventSessionId() != null) {
            EventSessionEntity eventSession = eventBO.findEventSessionById(requestSessionInfo.getEventSessionId());

            if(eventSession.getEvent().getBannedMpPowerups() != null) {
                if(eventSession.getEvent().getBannedMpPowerups().contains(powerupHash.toString())) {
                    openFireSoapBoxCli.send(XmppChat.createSystemMessage("SBRWR_DISABLEDPOWERUP"), activePersonaId);
                }
            } else if(parameterBO.getBoolParam("SBRWR_ENABLE_NOPU")) {
                if(eventSession.getNopuMode() == true) {
                    openFireSoapBoxCli.send(XmppChat.createSystemMessage("SBRWR_NOPU_MODE_ENABLED"), activePersonaId);
                } else {
                    sendPowerup(powerupHash, targetId, receivers, activePersonaId);
                }
            } else if(eventSession.getLobby() != null) {
                if(parameterBO.getStrParam("SBRWR_BANNED_MP_POWERUPS", "").contains(powerupHash.toString())) {
                    openFireSoapBoxCli.send(XmppChat.createSystemMessage("SBRWR_DISABLEDPOWERUP"), activePersonaId);
                }
            } else {
                sendPowerup(powerupHash, targetId, receivers, activePersonaId);
            }
        } else {
            sendPowerup(powerupHash, targetId, receivers, activePersonaId);
        }

        return "";
    }

    public void sendPowerup(Integer powerupHash, Long targetId, String receivers, Long activePersonaId) {
        XMPP_ResponseTypePowerupActivated powerupActivatedResponse = new XMPP_ResponseTypePowerupActivated();
        XMPP_PowerupActivatedType powerupActivated = new XMPP_PowerupActivatedType();
        powerupActivated.setId(Long.valueOf(powerupHash));
        powerupActivated.setTargetPersonaId(targetId);
        powerupActivated.setPersonaId(activePersonaId);
        powerupActivatedResponse.setPowerupActivated(powerupActivated);

        for (String receiver : receivers.split("-")) {
            long receiverPersonaId = Long.parseLong(receiver);
            if (receiverPersonaId > 10) {
                openFireSoapBoxCli.send(powerupActivatedResponse, receiverPersonaId);
            }
        }

        if (parameterBO.getBoolParam("ENABLE_POWERUP_DECREASE")) {
            PersonaEntity personaEntity = personaBO.getPersonaById(activePersonaId);
            InventoryItemEntity inventoryItemEntity = inventoryBO.decreaseItemCount(inventoryBO.getInventory(personaEntity), powerupHash);
            AchievementTransaction transaction = achievementBO.createTransaction(activePersonaId);
            transaction.add("INVENTORY", Map.of("persona", personaEntity, "ctx", new AchievementInventoryContext(inventoryItemEntity, AchievementInventoryContext.Event.QUANTITY_DECREASED)));
            achievementBO.commitTransaction(personaEntity, transaction);
        }

        usedPowerupBO.createPowerupRecord(requestSessionInfo.getEventSessionId(), activePersonaId, powerupHash);
    }
}
