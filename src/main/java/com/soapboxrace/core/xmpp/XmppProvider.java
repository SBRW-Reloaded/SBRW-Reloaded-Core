/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.xmpp;

import javax.ejb.Local;
import java.util.List;

@Local
public interface XmppProvider {
    boolean isEnabled();
    void createPersona(long personaId, String password);
    int getOnlineUserCount();
    List<Long> getAllPersonasInGroup(long personaId);
    void sendChatAnnouncement(String message);
    void sendMessage(long recipient, String message);
}
