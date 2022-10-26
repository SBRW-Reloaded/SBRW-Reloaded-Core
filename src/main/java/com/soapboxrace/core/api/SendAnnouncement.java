/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.api;

import com.soapboxrace.core.bo.AchievementBO;
import com.soapboxrace.core.bo.ParameterBO;
import com.soapboxrace.core.bo.util.HelpingTools;
import com.soapboxrace.core.xmpp.OpenFireRestApiCli;
import com.soapboxrace.core.xmpp.OpenFireSoapBoxCli;
import com.soapboxrace.core.jpa.PersonaEntity;
import com.soapboxrace.core.dao.PersonaDAO;

import javax.ejb.EJB;
import javax.ws.rs.FormParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.POST;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

@Path("/Send")
public class SendAnnouncement {

    @EJB
    private OpenFireRestApiCli openFireRestApiCli;

    @EJB
    private ParameterBO parameterBO;

    @EJB
    private PersonaDAO personaDAO;

    @EJB
    private AchievementBO achievementBo;

    @EJB
    private OpenFireSoapBoxCli openFireSoapBoxCli;

    @POST
    @Produces(MediaType.TEXT_HTML)
    @Path("/Announcement")
    public String sendAnnouncement(@FormParam("message") String message, @FormParam("announcementAuth") String token) {
        String announcementToken = parameterBO.getStrParam("ANNOUNCEMENT_AUTH");

        if (announcementToken == null) {
            return "ERROR! no announcement token set in DB";
        }

        if (announcementToken.equals(token)) {
            openFireRestApiCli.sendChatAnnouncement(message);
            return "SUCCESS! sent announcement";
        } else {
            return "ERROR! invalid admin token";
        }
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    @Path("/Alert")
    public String sendAlert(@QueryParam("announcementAuth") String token, @QueryParam("message") String message, @QueryParam("pid") Long personaId) {
        String announcementToken = parameterBO.getStrParam("ANNOUNCEMENT_AUTH");
        if (announcementToken == null) {
            return "ERROR! no announcement token set in DB";
        }

        if (announcementToken.equals(token)) {
            PersonaEntity personaEntity = personaDAO.find(personaId);
            if(personaEntity == null) {
                return "ERROR! User not found!";
            }
            
            HelpingTools.broadcastUICustom(personaEntity, message, "ADMINALERT", 5, openFireSoapBoxCli);
            return "SUCCESS!";
        } else {
            return "ERROR! invalid admin token";
        }
    }
}
