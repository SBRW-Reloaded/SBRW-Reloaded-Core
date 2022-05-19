package com.soapboxrace.core.bo.commands;

import javax.ws.rs.core.Response;
import java.util.List;

import com.soapboxrace.core.bo.*;
import com.soapboxrace.core.dao.*;
import com.soapboxrace.core.jpa.*;
import com.soapboxrace.core.xmpp.*;

public class NoPowerups {
    public Response Command(TokenSessionBO tokenSessionBO, ParameterBO parameterBO, PersonaEntity personaEntity, LobbyDAO lobbyDAO, OpenFireSoapBoxCli openFireSoapBoxCli, LobbyEntrantDAO lobbyEntrantDAO) {
        if(parameterBO.getBoolParam("SBRWR_ENABLE_NOPU")) {
            TokenSessionEntity tokendata = tokenSessionBO.findByUserId(personaEntity.getUser().getId());

            if(tokendata == null) {
                return Response.status(Response.Status.BAD_REQUEST).entity("can't find valid token for user").build();
            }

            Long getActiveLobbyId = 0L;
            Long getEventSessionId = 0L;

            if(tokendata.getActiveLobbyId() != null) getActiveLobbyId = tokendata.getActiveLobbyId();
            if(tokendata.getEventSessionId() != null) getEventSessionId = tokendata.getEventSessionId();

            if(getActiveLobbyId != 0L && getEventSessionId == 0) {
                LobbyEntity lobbyEntities = lobbyDAO.findById(getActiveLobbyId);

                //Disable command execution if lobby was not found
                if(lobbyEntities == null) {
                    return Response.noContent().build();
                }

                //Disable command execution for meetingplace and drag events
                if(lobbyEntities.getEvent().getEventModeId() == 19 || lobbyEntities.getEvent().getEventModeId() == 22) {
                    return Response.noContent().build();
                }

                //Disable command if join time is less than 5 seconds
                if(lobbyEntities.getLobbyCountdownInMilliseconds(lobbyEntities.getEvent().getLobbyCountdownTime()) <= 5000) {
                    openFireSoapBoxCli.send(XmppChat.createSystemMessage("SBRWR_NOPU_WARNING_VOTEENDED"), personaEntity.getPersonaId());
                    return Response.noContent().build();
                }

                List<LobbyEntrantEntity> lobbyEntrants = lobbyEntities.getEntrants();

                Integer totalVotes = lobbyEntrantDAO.getVotes(lobbyEntities)+1;
                Integer totalUsersInLobby = lobbyEntrants == null ? 1 : lobbyEntrants.size();

                if(totalUsersInLobby >= 2) {
                    if(lobbyEntrantDAO.getVoteStatus(personaEntity, lobbyEntities).getNopuMode()) {
                        openFireSoapBoxCli.send(XmppChat.createSystemMessage("SBRWR_NOPU_WARNING_ALREADYVOTED"), personaEntity.getPersonaId());
                    } else {
                        lobbyEntrantDAO.updateVoteByPersonaAndLobby(personaEntity, lobbyEntities);

                        if(parameterBO.getBoolParam("SBRWR_NOPU_ENABLE_VOTEMESSAGES")) {
                            for (LobbyEntrantEntity lobbyEntrant : lobbyEntrants) {
                                openFireSoapBoxCli.send(XmppChat.createSystemMessage("SBRWR_NOPU_USERVOTED," + personaEntity.getName() + "," + totalVotes + "," + totalUsersInLobby), lobbyEntrant.getPersona().getPersonaId());
                            }
                        }
                    }
                }
            } else if(getActiveLobbyId != 0L && getEventSessionId != 0) {
                if(parameterBO.getBoolParam("SBRWR_NOPU_ENABLE_WARNING_ONEVENT")) {
                    openFireSoapBoxCli.send(XmppChat.createSystemMessage("SBRWR_NOPU_WARNING_ONEVENT"), personaEntity.getPersonaId());
                }                
            } else {
                if(parameterBO.getBoolParam("SBRWR_NOPU_ENABLE_WARNING_ONFREEROAM")) {
                    openFireSoapBoxCli.send(XmppChat.createSystemMessage("SBRWR_NOPU_WARNING_ONFREEROAM"), personaEntity.getPersonaId());
                }
            }
        }

        return Response.noContent().build();
    }
}