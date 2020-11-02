/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.bo;

import com.soapboxrace.core.api.util.MiscUtils;
import com.soapboxrace.core.dao.BanDAO;
import com.soapboxrace.core.dao.HardwareInfoDAO;
import com.soapboxrace.core.dao.PersonaDAO;
import com.soapboxrace.core.dao.UserDAO;
import com.soapboxrace.core.jpa.BanEntity;
import com.soapboxrace.core.jpa.HardwareInfoEntity;
import com.soapboxrace.core.jpa.PersonaEntity;
import com.soapboxrace.core.jpa.UserEntity;
import com.soapboxrace.core.xmpp.OpenFireSoapBoxCli;
import com.soapboxrace.core.xmpp.XmppChat;

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
    private UserDAO userDao;

    @EJB
    private DiscordWebhook discord;
    
    @EJB
	private ParameterBO parameterBO;

    @EJB
    private BanDAO banDAO;

    @EJB
    private HardwareInfoDAO hardwareInfoDAO;

    @EJB
    private OpenFireSoapBoxCli openFireSoapBoxCli;

    public void sendChatCommand(Long personaId, String command, String personaName) {
        String personaToBan = command.split(" ")[1];
        PersonaEntity personaEntity = personaDao.findByName(personaToBan);
        command = command.replace(personaToBan + " ", "");
		sendCommand(personaId, personaEntity.getPersonaId(), command);
	}

    public void sendCommand(Long personaId, Long abuserPersonaId, String command) {
        CommandInfo commandInfo = CommandInfo.parse(command);
        PersonaEntity personaEntity = personaDao.findById(abuserPersonaId);
		PersonaEntity personaEntity1 = personaDao.findById(personaId);

		if (personaEntity == null && personaEntity1 == null)
			return;

        String constructMsg = "[ " + personaEntity.getName() + " ] has been %s by [ " + personaEntity1.getName() + " ].";
        String constructMsg_ds = "**" + personaEntity.getName() + "** has been %s by **" + personaEntity1.getName() + "**";

        switch (commandInfo.action) {
            case BAN:
                if (banDAO.findByUser(personaEntity.getUser()) != null) {
                    openFireSoapBoxCli.send(XmppChat.createSystemMessage("Oh no, this user is already banned..."), personaId);
                    break;
                }

                sendBan(personaEntity, personaDao.findById(personaId), commandInfo.timeEnd, commandInfo.reason);
                openFireSoapBoxCli.send(XmppChat.createSystemMessage("Yay, user has been banned."), personaId);

				if(parameterBO.getStrParam("DISCORD_WEBHOOK_BANREPORT_URL") != null) {
					discord.sendMessage(constructMsg_ds.replace("%s", "banned") + ". Reason: " + commandInfo.reason, 
						parameterBO.getStrParam("DISCORD_WEBHOOK_BANREPORT_URL"), 
						parameterBO.getStrParam("DISCORD_WEBHOOK_BANREPORT_NAME", "Botte"),
						0xff0000
					);
				}

                if(parameterBO.getStrParam("DISCORD_WEBHOOK_BANREPORT_PUBLIC_URL") != null) {
					discord.sendMessage(constructMsg_ds.replace("%s", "banned") + ". Reason: " + commandInfo.reason, 
						parameterBO.getStrParam("DISCORD_WEBHOOK_BANREPORT_PUBLIC_URL"), 
						parameterBO.getStrParam("DISCORD_WEBHOOK_BANREPORT_NAME", "Botte"),
						0xff0000
					);
				}

                break;
            case KICK:
                sendKick(personaEntity.getUser().getId(), personaEntity.getPersonaId());
                openFireSoapBoxCli.send(XmppChat.createSystemMessage("Kicked out the butt of the user."), personaId);

				if(parameterBO.getStrParam("DISCORD_WEBHOOK_BANREPORT_URL") != null) {
					discord.sendMessage(constructMsg_ds.replace("%s", "kicked"), 
						parameterBO.getStrParam("DISCORD_WEBHOOK_BANREPORT_URL"), 
						parameterBO.getStrParam("DISCORD_WEBHOOK_BANREPORT_NAME", "Botte"),
						0xfff200
					);
				}

                break;
            case UNBAN:
                BanEntity existingBan;
                if ((existingBan = banDAO.findByUser(personaEntity.getUser())) == null) {
                    openFireSoapBoxCli.send(XmppChat.createSystemMessage("Why you wanna unban that user ? Isn't even banned !"), personaId);
                    break;
                }

                if(parameterBO.getStrParam("DISCORD_WEBHOOK_BANREPORT_URL") != null) {
					discord.sendMessage(constructMsg_ds.replace("%s", "unbanned"), 
						parameterBO.getStrParam("DISCORD_WEBHOOK_BANREPORT_URL"), 
						parameterBO.getStrParam("DISCORD_WEBHOOK_BANREPORT_NAME", "Botte"),
						0x1aff00
					);
				}

                banDAO.delete(existingBan);
                openFireSoapBoxCli.send(XmppChat.createSystemMessage("The user has been unbanned, I hope we will not have to ban it once again."), personaId);

                break;
            default:
                break;
        }
    }

    private void sendBan(PersonaEntity personaEntity, PersonaEntity bannedBy, LocalDateTime endsOn, String reason) {
        UserEntity userEntity = personaEntity.getUser();
        BanEntity banEntity = new BanEntity();
        banEntity.setUserEntity(userEntity);
        banEntity.setEndsAt(endsOn);
        banEntity.setStarted(LocalDateTime.now());
        banEntity.setReason(reason);
        banEntity.setBannedBy(bannedBy);
        banEntity.setWillEnd(endsOn != null);
        banDAO.insert(banEntity);
        userDao.update(userEntity);
        sendKick(userEntity.getId(), personaEntity.getPersonaId());

        HardwareInfoEntity hardwareInfoEntity = hardwareInfoDAO.findByUserId(userEntity.getId());

        if (hardwareInfoEntity != null) {
            hardwareInfoEntity.setBanned(true);
            hardwareInfoDAO.update(hardwareInfoEntity);
        }
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

            switch (action) {
                case BAN: {
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
                    break;
                }
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