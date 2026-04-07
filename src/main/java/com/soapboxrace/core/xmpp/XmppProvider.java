/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.xmpp;

import java.util.List;
import javax.ejb.Local;

@Local
public interface XmppProvider {
    boolean isEnabled();
    void createPersona(long personaId, String password);
    int getOnlineUserCount();
    List<Long> getAllPersonasInGroup(long personaId);
    void sendChatAnnouncement(String message);
    void sendMessage(long recipient, String message);
    
    /**
     * Vérifie si un persona a une session XMPP active (est connecté)
     * @param personaId ID du persona
     * @return true si le persona a au moins une session active
     */
    boolean isPersonaOnline(long personaId);
    
    /**
     * Retire un joueur d'une room XMPP spécifique par son nom.
     * @param personaId ID du persona
     * @param roomName  Nom exact de la room (ex: "502_328")
     */
    void removePersonaFromRoom(long personaId, String roomName);

    /**
     * Kick un occupant d'une room XMPP (retire la présence active).
     * Contrairement à removePersonaFromRoom qui retire l'affiliation,
     * cette méthode force le retrait de la présence XMPP de l'occupant.
     * @param personaId ID du persona
     * @param roomName  Nom exact de la room (ex: "502_328")
     */
    void kickOccupantFromRoom(long personaId, String roomName);
}
