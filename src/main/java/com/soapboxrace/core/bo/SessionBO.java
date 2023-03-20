/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.bo;


import com.soapboxrace.core.api.util.GeoIp2;
import com.soapboxrace.core.dao.ChatRoomDAO;
import com.soapboxrace.core.dao.KCrewMemberDAO;
import com.soapboxrace.core.jpa.ChatRoomEntity;
import com.soapboxrace.core.jpa.KCrewEntity;
import com.soapboxrace.core.jpa.KCrewMemberEntity;
import com.soapboxrace.jaxb.http.ArrayOfChatRoom;
import com.soapboxrace.jaxb.http.ChatRoom;

import javax.ejb.EJB;
import javax.ejb.Stateless;

import java.util.List;

@Stateless
public class SessionBO {

    @EJB
    private ChatRoomDAO chatRoomDao;

    @EJB
    private ParameterBO parameterBO;
        
    @EJB
    private KCrewMemberDAO kCrewMemberDAO;

    public ArrayOfChatRoom getAllChatRoom(String userId, String ip) {
        List<ChatRoomEntity> chatRoomList = chatRoomDao.findAll();
        ArrayOfChatRoom arrayOfChatRoom = new ArrayOfChatRoom();
        for (ChatRoomEntity entity : chatRoomList) {
            if(entity.getShortName().equals("CREW")) {
                if(!userId.isEmpty()) {
                    KCrewMemberEntity kCrewMemberEntity = kCrewMemberDAO.findCrewMembershipByUserId(Long.parseLong(userId));
                    if(kCrewMemberEntity != null) {
                        KCrewEntity kCrewEntity = kCrewMemberEntity.getCrew();
                        if(kCrewEntity != null) {
                            ChatRoom chatRoom = new ChatRoom();
                            chatRoom.setChannelCount(entity.getAmount());
                            chatRoom.setLongName(entity.getLongName());
                            chatRoom.setShortName(kCrewEntity.getTag());
                            arrayOfChatRoom.getChatRoom().add(chatRoom);
                        }
                    }
                }
            } else if(entity.getShortName().equals("GEOCHAT")) {
                GeoIp2 geoIp2 = GeoIp2.getInstance(parameterBO.getStrParam("GEOIP2_DB_FILE_PATH"));
                
                ChatRoom chatRoomCountry = new ChatRoom();
                chatRoomCountry.setChannelCount(entity.getAmount());
                chatRoomCountry.setLongName(entity.getLongName());
                chatRoomCountry.setShortName(geoIp2.getCountryIso(ip));
                arrayOfChatRoom.getChatRoom().add(chatRoomCountry);
            } else {
                ChatRoom chatRoom = new ChatRoom();
                chatRoom.setChannelCount(entity.getAmount());
                chatRoom.setLongName(entity.getLongName());
                chatRoom.setShortName(entity.getShortName());
                arrayOfChatRoom.getChatRoom().add(chatRoom);
            }
        }

        return arrayOfChatRoom;
    }
}
