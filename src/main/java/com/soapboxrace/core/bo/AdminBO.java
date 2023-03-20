/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.bo;

import com.soapboxrace.core.api.util.MiscUtils;
import com.soapboxrace.core.dao.PersonaDAO;
import com.soapboxrace.core.jpa.BanEntity;
import com.soapboxrace.core.jpa.PersonaEntity;
import com.soapboxrace.core.jpa.UserEntity;
import com.soapboxrace.core.xmpp.OpenFireSoapBoxCli;
import com.soapboxrace.core.xmpp.OpenFireRestApiCli;
import com.soapboxrace.core.xmpp.XmppChat;
import com.soapboxrace.core.dao.UserDAO;

import com.soapboxrace.core.bo.util.DiscordWebhook;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import java.time.LocalDateTime;

@Stateless
public class AdminBO {
    @EJB
    private TokenSessionBO tokenSessionBo;

    @EJB
    private PersonaDAO personaDao;

    @EJB
    private BanBO banBO;

    @EJB
    private DiscordWebhook discord;
    
    @EJB
	private ParameterBO parameterBO;

    @EJB
    private OpenFireSoapBoxCli openFireSoapBoxCli;

    @EJB
    private OpenFireRestApiCli openFireRestApiCli;

    @EJB
    private UserDAO userDAO;

    public void sendChatCommand(Long personaId, String command, String personaName) {
        sendChatCommand(personaId, command, personaName, true);
    }

    public void sendChatCommand(Long personaId, String command, String personaName, Boolean sendWebHookAction) {
        try {
            String personaToBan = command.split(" ")[1];
            PersonaEntity personaEntity = personaDao.findByName(personaToBan);
            String commandNoPersonaName = command.replace(personaEntity.getName(), "");
            sendCommand(personaId, personaEntity.getPersonaId(), commandNoPersonaName.trim(), sendWebHookAction);
        } catch (Exception e) {
            e.printStackTrace();
        }
	}

    public void sendCommand(Long personaId, Long abuserPersonaId, String command) {
        sendCommand(personaId, abuserPersonaId, command, true);
    }

    public void sendCommand(Long personaId, Long abuserPersonaId, String command, Boolean sendWebHookAction) {
        CommandInfo commandInfo = CommandInfo.parse(command);
        PersonaEntity personaEntity = personaDao.find(abuserPersonaId);
        PersonaEntity personaEntity1 = personaDao.find(personaId);

		if (personaEntity == null || personaEntity1 == null)
            return; 

        String constructMsg = "[" + personaEntity.getName() + "] has been %s by [" + personaEntity1.getName() + "].";
        String constructMsg_ds = "**" + personaEntity.getName() + "** has been %s by **" + personaEntity1.getName() + "**";

        UserEntity userEntity = personaEntity.getUser();
        switch (commandInfo.action) {
            case BAN:
                if (banBO.isBanned(userEntity)) {
                    openFireSoapBoxCli.send(XmppChat.createSystemMessage("Oh no, this user is already banned.."), personaId);
                    break;
                }

                if(parameterBO.getBoolParam("SBRWR_SEND_ADMIN_ACTION")) {
                    openFireRestApiCli.sendChatAnnouncement(constructMsg.replace("%s", "banned"));
                }
                
                String reason = (commandInfo.reason == null) ? parameterBO.getStrParam("DEFAULT_BAN_REASON", "No reason provided.") : commandInfo.reason;

                sendBan(personaEntity, personaDao.find(personaId), commandInfo.timeEnd, reason);
                openFireSoapBoxCli.send(XmppChat.createSystemMessage("Yay, user has been banned."), personaId);

                if(sendWebHookAction == true) {
                    if(parameterBO.getStrParam("DISCORD_WEBHOOK_BANREPORT_URL") != null) {
                        discord.sendMessage(constructMsg_ds.replace("%s", "banned") + ". Reason: " + reason, 
                            parameterBO.getStrParam("DISCORD_WEBHOOK_BANREPORT_URL"), 
                            parameterBO.getStrParam("DISCORD_WEBHOOK_BANREPORT_NAME", "Botte"),
                            0xff0000
                        );
                    }

                    if(parameterBO.getStrParam("DISCORD_WEBHOOK_BANREPORT_PUBLIC_URL") != null) {
                        discord.sendMessage(constructMsg_ds.replace("%s", "banned") + ". Reason: " + reason, 
                            parameterBO.getStrParam("DISCORD_WEBHOOK_BANREPORT_PUBLIC_URL"), 
                            parameterBO.getStrParam("DISCORD_WEBHOOK_BANREPORT_NAME", "Botte"),
                            0xff0000
                        );
                    }
                }

                break;
            case KICK:
                if(parameterBO.getBoolParam("SBRWR_SEND_ADMIN_ACTION")) {
                    openFireRestApiCli.sendChatAnnouncement(constructMsg.replace("%s", "kicked"));
                }

                sendKick(userEntity.getId(), personaEntity.getPersonaId());
                openFireSoapBoxCli.send(XmppChat.createSystemMessage("Kicked out the butt of the user."), personaId);

                if(sendWebHookAction == true) {
                    if(parameterBO.getStrParam("DISCORD_WEBHOOK_BANREPORT_URL") != null) {
                        discord.sendMessage(constructMsg_ds.replace("%s", "kicked"), 
                            parameterBO.getStrParam("DISCORD_WEBHOOK_BANREPORT_URL"), 
                            parameterBO.getStrParam("DISCORD_WEBHOOK_BANREPORT_NAME", "Botte"),
                            0xfff200
                        );
                    }
                }
                
                break;
            case UNBAN:
                BanEntity existingBan;
                if ((existingBan = banBO.getUserBan(userEntity)) == null) {
                    openFireSoapBoxCli.send(XmppChat.createSystemMessage("Why you wanna unban that user ? Isn't even banned !"), personaId);
                    break;
                }

                if(parameterBO.getBoolParam("SBRWR_SEND_ADMIN_ACTION")) {
                    openFireRestApiCli.sendChatAnnouncement(constructMsg.replace("%s", "unbanned"));
                }

                if(sendWebHookAction == true) {
                    if(parameterBO.getStrParam("DISCORD_WEBHOOK_BANREPORT_URL") != null) {
                        discord.sendMessage(constructMsg_ds.replace("%s", "unbanned"), 
                            parameterBO.getStrParam("DISCORD_WEBHOOK_BANREPORT_URL"), 
                            parameterBO.getStrParam("DISCORD_WEBHOOK_BANREPORT_NAME", "Botte"),
                            0x1aff00
                        );
                    }
                }

                banBO.expireBan(existingBan);
                
                //Re-check USER table if user is locked
                UserEntity bannedUser = existingBan.getUserEntity();
                if(bannedUser.isLocked()) {
                    bannedUser.setLocked(false);
                    userDAO.update(bannedUser);
                }
                
                openFireSoapBoxCli.send(XmppChat.createSystemMessage("The user has been unbanned, I hope we will not have to ban it once again."), personaId);

                break;
            default:
                break;
        }
    }

    private void sendBan(PersonaEntity personaEntity, PersonaEntity bannedBy, LocalDateTime endsOn, String reason) {
        UserEntity userEntity = personaEntity.getUser();
        banBO.banUser(userEntity, bannedBy, reason, endsOn);
        sendKick(userEntity.getId(), personaEntity.getPersonaId());
    }

    private void sendKick(Long userId, Long personaId) {
        openFireSoapBoxCli.send("<NewsArticleTrans><ExpiryTime><", personaId);
        tokenSessionBo.deleteByUserId(userId);
    }

    private static class CommandInfo {
        public CommandInfo.CmdAction action;
        public String reason;
        public LocalDateTime timeEnd;

        public static CommandInfo parse(String cmd) {
            cmd = cmd.replaceFirst("/", "");

            String[] split = cmd.split(" ");
            CommandInfo.CmdAction action;
            CommandInfo info = new CommandInfo();

            switch (split[0].toLowerCase().trim()) {
                case "ban":
                    action = CmdAction.BAN;
                    break;
                case "kick":
                    action = CmdAction.KICK;
                    break;
                case "unban":
                    action = CmdAction.UNBAN;
                    break;
                default:
                    action = CmdAction.UNKNOWN;
                    break;
            }

            info.action = action;

            if (action == CmdAction.BAN) {
                LocalDateTime endTime;
                String reason = null;

                if (split.length >= 2) {
                    long givenTime = MiscUtils.lengthToMiliseconds(split[1]);
                    if (givenTime != 0) {
                        endTime = LocalDateTime.now().plusSeconds(givenTime / 1000);
                        info.timeEnd = endTime;

                        if (split.length > 2) {
                            reason = MiscUtils.argsToString(split, 2, split.length);
                        }
                    } else {
                        reason = MiscUtils.argsToString(split, 1, split.length);
                    }
                }

                info.reason = reason;
            }

            return info;
        }

        public enum CmdAction {
            KICK,
            BAN,
            ALERT,
            UNBAN,
            UNKNOWN
        }
    }
}