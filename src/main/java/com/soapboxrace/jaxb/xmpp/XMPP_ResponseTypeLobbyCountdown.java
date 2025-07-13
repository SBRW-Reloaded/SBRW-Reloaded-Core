/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

 package com.soapboxrace.jaxb.xmpp;

 import com.soapboxrace.jaxb.http.LobbyCountdown;
 
 import javax.xml.bind.annotation.*;
 
 @XmlAccessorType(XmlAccessType.FIELD)
 @XmlType(name = "XMPP_ResponseTypeLobbyCountdown", propOrder = {"lobbyCountdown"})
 @XmlRootElement(name = "response")
 public class XMPP_ResponseTypeLobbyCountdown {
     @XmlElement(name = "LobbyCountdown", required = true)
     protected LobbyCountdown lobbyCountdown;
     @XmlAttribute(name = "status")
     protected int status = 1;
     @XmlAttribute(name = "ticket")
     protected int ticket = 0;
 
     public LobbyCountdown getLobbyCountdown() {
         return lobbyCountdown;
     }
 
     public void setLobbyCountdown(LobbyCountdown lobbyCountdown) {
         this.lobbyCountdown = lobbyCountdown;
     }
 
     public int getStatus() {
         return status;
     }
 
     public void setStatus(int status) {
         this.status = status;
     }
 
     public int getTicket() {
         return ticket;
     }
 
     public void setTicket(int ticket) {
         this.ticket = ticket;
     }
 }