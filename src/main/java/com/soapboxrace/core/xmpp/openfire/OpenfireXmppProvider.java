/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.xmpp.openfire;

import com.soapboxrace.core.bo.ParameterBO;
import com.soapboxrace.core.dao.ChatRoomDAO;
import com.soapboxrace.core.jpa.ChatRoomEntity;
import com.soapboxrace.core.xmpp.XmppProvider;
import org.igniterealtime.restclient.entity.MUCRoomEntities;
import org.igniterealtime.restclient.entity.MUCRoomEntity;
import org.igniterealtime.restclient.entity.UserEntity;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation.Builder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
@Named("OpenfireXmppProvider")
public class OpenfireXmppProvider implements XmppProvider {
    private static final Logger logger = LoggerFactory.getLogger(OpenfireXmppProvider.class);
    
    private String openFireToken;
    private String openFireAddress;
    private String xmppIp;

    @Inject
    private ParameterBO parameterBO;

    @Inject
    private ChatRoomDAO chatRoomDAO;

    @Inject
    private OpenFireConnector openFireConnector;

    @PostConstruct
    public void init() {
        openFireToken = parameterBO.getStrParam("OPENFIRE_TOKEN");
        openFireAddress = parameterBO.getStrParam("OPENFIRE_ADDRESS");
        xmppIp = parameterBO.getStrParam("XMPP_IP");
        if (parameterBO.getStrParam("XMPP_PROVIDER").equals("OPENFIRE")) {
            createUpdatePersona("sbrw.engine.engine", openFireToken);

            for (ChatRoomEntity chatRoomEntity : chatRoomDAO.findAll()) {
                for (int i = 1; i <= chatRoomEntity.getAmount(); i++) {
                    createGeneralChatRoom(chatRoomEntity.getShortName(), i);
                }
            }

            openFireConnector.connect();
        }
    }

    @Override
    public boolean isEnabled() {
        return parameterBO.getStrParam("XMPP_PROVIDER").equals("OPENFIRE");
    }

    private Builder getBuilder(String path) {
        return getBuilder(path, null);
    }

    private Builder getBuilder(String path, Map<String, Object> query) {
        Client client = ClientBuilder.newClient();
        WebTarget target = client.target(openFireAddress).path(path);

        if (query != null) {
            for (Map.Entry<String, Object> queryEntry : query.entrySet()) {
                target = target.queryParam(queryEntry.getKey(), queryEntry.getValue());
            }
        }

        Builder request = target.request(MediaType.APPLICATION_XML);
        request.header("Authorization", openFireToken);
        return request;
    }

    private void createUpdatePersona(String user, String password) {
        Builder builder = getBuilder("users/" + user);
        Response response = builder.get();
        if (response.getStatus() == 200) {
            response.close();
            UserEntity userEntity = builder.get(UserEntity.class);
            userEntity.setPassword(password);
            builder = getBuilder("users/" + user);
            builder.put(Entity.entity(userEntity, MediaType.APPLICATION_XML));
        } else {
            response.close();
            builder = getBuilder("users");
            UserEntity userEntity = new UserEntity(user, null, null, password);
            builder.post(Entity.entity(userEntity, MediaType.APPLICATION_XML));
        }
        response.close();
    }

    @Override
    public void createPersona(long personaId, String password) {
        String user = "sbrw." + personaId;
        createUpdatePersona(user, password);
    }

    @Override
    public int getOnlineUserCount() {
        Builder builder = getBuilder("system/statistics/sessions");
        SessionsCount sessionsCount = builder.get(SessionsCount.class);
        int clusterSessions = sessionsCount.getClusterSessions();
        if (clusterSessions > 1) {
            return clusterSessions - 1;
        }
        return 0;
    }

    @Override
    public boolean isPersonaOnline(long personaId) {
        try {
            String userName = parameterBO.getStrParam("SBRWR_XMPP_APPEND", "sbrw") + "." + personaId;
            Builder builder = getBuilder("users/" + userName + "/sessions");
            
            // Si l'utilisateur a des sessions actives, l'API renvoie 200 avec une liste
            // Si pas de sessions, l'API peut renvoyer 200 avec liste vide ou 404
            Response response = builder.get();
            
            if (response.getStatus() == 200) {
                // Lire la réponse pour vérifier s'il y a des sessions
                String responseBody = response.readEntity(String.class);
                // Si la réponse contient des sessions, le joueur est en ligne
                boolean hasActiveSessions = responseBody != null && 
                                            !responseBody.trim().isEmpty() && 
                                            !responseBody.contains("<sessions/>") &&
                                            !responseBody.contains("\"sessions\":[]");
                
                logger.debug("Persona {} XMPP session check: {}", personaId, hasActiveSessions);
                return hasActiveSessions;
            } else {
                // 404 ou autre erreur = pas de session
                logger.debug("Persona {} has no XMPP sessions (status: {})", personaId, response.getStatus());
                return false;
            }
        } catch (Exception e) {
            // En cas d'erreur (réseau, etc.), supposer offline par sécurité
            logger.debug("Error checking XMPP session for persona {}: {}", personaId, e.getMessage());
            return false;
        }
    }

    @Override
    public List<Long> getAllPersonasInGroup(long personaId) {
        try {
            String userName = parameterBO.getStrParam("SBRWR_XMPP_APPEND", "sbrw") + "." + personaId;
            
            Builder builder = getBuilder("chatrooms/forUser",
                    Map.of(
                            "userName", userName,
                            "domain", xmppIp,
                            "resource", "EA-Chat"));
            MUCRoomEntities roomEntities = builder.get(MUCRoomEntities.class);
            List<MUCRoomEntity> listRoomEntity = roomEntities.getMucRooms();
            
            if (listRoomEntity != null) {
                for (MUCRoomEntity entity : listRoomEntity) {
                    String roomName = entity.getRoomName();
                    
                    if (roomName.contains("group.channel.")) {
                        return getAllOccupantsInRoom(roomName);
                    }
                }
            }
            
            return new ArrayList<>();
        } catch (Exception e) {
            logger.error(String.format("Error getting group members for PersonaId=%d: %s",
                    personaId, e.getMessage()), e);
            return new ArrayList<>();
        }
    }

    private List<Long> getAllOccupantsInRoom(String roomName) {
        try {
            Builder builder = getBuilder("chatrooms/" + roomName + "/occupants");
            OccupantEntities occupantEntities = builder.get(OccupantEntities.class);
            List<Long> listOfPersona = new ArrayList<>();
            
            if (occupantEntities != null && occupantEntities.getOccupants() != null) {
                for (OccupantEntity entity : occupantEntities.getOccupants()) {
                    String jid = entity.getJid();
                    
                    try {
                        Long personaId = Long.parseLong(jid.substring(jid.lastIndexOf('.') + 1));
                        listOfPersona.add(personaId);
                    } catch (Exception e) {
                        logger.warn(String.format("Failed to extract PersonaId from JID=%s: %s", jid, e.getMessage()));
                    }
                }
            }
            
            return listOfPersona;
        } catch (Exception e) {
            logger.error(String.format("Error getting occupants for room %s: %s", roomName, e.getMessage()), e);
            return new ArrayList<>();
        }
    }

    @Override
    public void sendChatAnnouncement(String message) {
        Builder builder = getBuilder("messages/game");
        builder.post(Entity.entity(message, MediaType.TEXT_PLAIN_TYPE));
    }

    @Override
    public void sendMessage(long recipient, String message) {
        openFireConnector.send(message, recipient);
    }

    private void createGeneralChatRoom(String language, Integer number) {
        String name = "channel." + language + "__" + number;
        Builder builder = getBuilder("chatrooms");
        MUCRoomEntity mucRoomEntity = new MUCRoomEntity();
        mucRoomEntity.setRoomName(name);
        mucRoomEntity.setNaturalName(name);
        mucRoomEntity.setDescription(name);
        mucRoomEntity.setMaxUsers(0);
        mucRoomEntity.setPersistent(true);
        mucRoomEntity.setBroadcastPresenceRoles(Arrays.asList("moderator", "participant", "visitor"));
        mucRoomEntity.setLogEnabled(true);

        builder.post(Entity.entity(mucRoomEntity, MediaType.APPLICATION_XML));
    }
    
    @Override
    public void removePersonaFromRoom(long personaId, String roomName) {
        String userName = parameterBO.getStrParam("SBRWR_XMPP_APPEND", "sbrw") + "." + personaId;
        String userJid = userName + "@" + xmppIp + "/EA-Chat";
        try {
            Builder builder = getBuilder("chatrooms/" + roomName + "/occupants/" + userJid);
            Response response = builder.delete();
            int status = response.getStatus();
            response.close();
            if (status == 200 || status == 204) {
                logger.info("XMPP: Removed persona {} from room {}", personaId, roomName);
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
        // Openfire gère le retrait de présence via le DELETE de removePersonaFromRoom
        removePersonaFromRoom(personaId, roomName);
    }

}
