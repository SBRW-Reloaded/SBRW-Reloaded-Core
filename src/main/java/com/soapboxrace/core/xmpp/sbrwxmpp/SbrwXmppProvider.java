/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.xmpp.sbrwxmpp;

import com.soapboxrace.core.bo.ParameterBO;
import com.soapboxrace.core.xmpp.XmppChat;
import com.soapboxrace.core.xmpp.XmppProvider;

import javax.annotation.PostConstruct;
import javax.ejb.EJB;
import javax.ejb.Singleton;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation.Builder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.List;

@Singleton
public class SbrwXmppProvider implements XmppProvider {
    private String sbrwxmppToken;
    private String sbrwxmppAddress;
    private String domain;
    private Client client;

    @EJB
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
}
