/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.xmpp.sbrwxmpp;

import com.soapboxrace.core.bo.ParameterBO;
import com.soapboxrace.core.xmpp.XmppChat;
import com.soapboxrace.core.xmpp.XmppProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation.Builder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
@Named("SbrwXmppProvider")
public class SbrwXmppProvider implements XmppProvider {
    private static final Logger logger = LoggerFactory.getLogger(SbrwXmppProvider.class);
    
    private String sbrwxmppToken;
    private String sbrwxmppAddress;
    private String domain;
    private Client client;

    @Inject
    private ParameterBO parameterBO;

    @PostConstruct
    public void init() {
        sbrwxmppToken = parameterBO.getStrParam("OPENFIRE_TOKEN");
        sbrwxmppAddress = parameterBO.getStrParam("OPENFIRE_ADDRESS");
        domain = parameterBO.getStrParam("XMPP_IP");
        client = ClientBuilder.newClient();
    }

    @Override
    public boolean isEnabled() {
        return parameterBO.getStrParam("XMPP_PROVIDER").equals("SBRWXMPP");
    }

    private Builder getBuilder(String path) {
        WebTarget target = client.target(sbrwxmppAddress).path(path);
        Builder request = target.request(MediaType.APPLICATION_XML);
        request.header("Authorization", sbrwxmppToken);
        return request;
    }

    @Override
    public void createPersona(long personaId, String password) {
        String user = "sbrw." + personaId;
        Builder builder = getBuilder("users");
        UserEntity userEntity = new UserEntity(user, password);
        builder.post(Entity.entity(userEntity, MediaType.APPLICATION_JSON)).close();
    }

    @Override
    public int getOnlineUserCount() {
        return getSessions().size();
    }

    private List<String> getSessions() {
        Builder builder = getBuilder("sessions");
        return builder.get(new GenericType<>() {
        });
    }

    @Override
    public boolean isPersonaOnline(long personaId) {
        try {
            // Vérifier si le persona est dans la liste des sessions actives
            List<String> activeSessions = getSessions();
            String personaName = "sbrw." + personaId;
            
            boolean isOnline = activeSessions.contains(personaName);
            logger.debug("Persona {} XMPP session check: {}", personaId, isOnline);
            return isOnline;
        } catch (Exception e) {
            // En cas d'erreur, supposer offline par sécurité
            logger.debug("Error checking XMPP session for persona {}: {}", personaId, e.getMessage());
            return false;
        }
    }

    private List<RoomEntity> getAllRooms() {
        Builder builder = getBuilder("rooms");
        return builder.get(new GenericType<>() {
        });
    }

    public List<Long> getAllPersonasInGroup(long personaId) {
        List<RoomEntity> roomEntities = getAllRooms();
        for (RoomEntity entity : roomEntities) {
            String roomName = entity.getName();
            if (roomName.startsWith("group.channel.")) {
                List<Long> groupMembers = namesToPersonas(entity.getMembers());
                if (groupMembers.contains(personaId)) {
                    return groupMembers;
                }
            }
        }
        return new ArrayList<>();
    }

    private List<Long> getOnlinePersonas() {
        List<String> entities = getSessions();
        return namesToPersonas(entities);
    }

    @Override
    public void sendMessage(long recipient, String message) {
        String to = "sbrw." + recipient;
        Builder builder = getBuilder("users/" + to + "/message");
        MessageEntity entity = new MessageEntity();
        entity.setBody(message);
        entity.setFrom("sbrw.engine.engine@" + domain + "/EA_Chat");
        entity.calculateHash(to + "@" + domain);
        builder.post(Entity.json(entity)).close();
    }

    @Override
    public void sendChatAnnouncement(String message) {
        String sysMessage = XmppChat.createSystemMessage(message);
        for (Long persona : getOnlinePersonas()) {
            sendMessage(persona, sysMessage);
        }
    }

    private List<Long> namesToPersonas(List<String> names) {
        List<Long> personaList = new ArrayList<>();

        for (String name : names) {
            try {
                Long personaId = Long.parseLong(name.substring(name.lastIndexOf('.') + 1));
                personaList.add(personaId);
            } catch (Exception e) {
                //
            }
        }
        return personaList;
    }
    
    @Override
    public void removePersonaFromRoom(long personaId, String roomName) {
        String userName = "sbrw." + personaId;
        try {
            Builder builder = getBuilder("rooms/" + roomName + "/members/" + userName);
            Response response = builder.delete();
            int status = response.getStatus();
            response.close();
            if (status == 200 || status == 204) {
                logger.info("XMPP: Removed persona {} from room {} (affiliation)", personaId, roomName);
            } else if (status == 404) {
                logger.debug("XMPP: Persona {} not in room {} or room gone", personaId, roomName);
            } else {
                logger.warn("XMPP: Failed to remove persona {} from room {} (status: {})", personaId, roomName, status);
            }
        } catch (Exception e) {
            logger.warn("XMPP: Error removing persona {} from room {}: {}", personaId, roomName, e.getMessage());
        }
    }

    @Override
    public void kickOccupantFromRoom(long personaId, String roomName) {
        String userName = "sbrw." + personaId;
        try {
            Builder builder = getBuilder("rooms/" + roomName + "/kick/" + userName);
            Response response = builder.post(null);
            int status = response.getStatus();
            response.close();
            if (status == 200) {
                logger.info("XMPP: Kicked occupant {} from room {} (SBRWXMPP)", personaId, roomName);
            } else if (status == 404) {
                logger.debug("XMPP: Occupant {} not in room {} or room gone (SBRWXMPP)", personaId, roomName);
            } else {
                logger.warn("XMPP: Failed to kick occupant {} from room {} (status: {}, SBRWXMPP)", personaId, roomName, status);
            }
        } catch (Exception e) {
            logger.warn("XMPP: Error kicking occupant {} from room {} (SBRWXMPP): {}", personaId, roomName, e.getMessage());
        }
    }

}
