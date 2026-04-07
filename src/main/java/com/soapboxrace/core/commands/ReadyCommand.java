package com.soapboxrace.core.commands;

import com.soapboxrace.core.bo.LobbyCountdownBO;
import com.soapboxrace.core.bo.ParameterBO;
import com.soapboxrace.core.bo.TokenSessionBO;
import com.soapboxrace.core.dao.LobbyDAO;
import com.soapboxrace.core.jpa.LobbyEntity;
import com.soapboxrace.core.jpa.LobbyEntrantEntity;
import com.soapboxrace.core.jpa.PersonaEntity;
import com.soapboxrace.core.jpa.TokenSessionEntity;
import com.soapboxrace.core.xmpp.OpenFireSoapBoxCli;
import com.soapboxrace.core.xmpp.XmppChat;
import com.soapboxrace.jaxb.http.LobbyCountdown;
import com.soapboxrace.jaxb.xmpp.XMPP_ResponseTypeLobbyCountdown;

import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Commande /ready ou /r permettant aux joueurs de signaler qu'ils sont prêts
 * Si 60% (configurable) des joueurs sont prêts, le countdown passe instantanément à 5 secondes
 */
public class ReadyCommand {
    // Set statique pour stocker les votes "ready" par lobby
    // Format de la clé: "lobbyId_personaId"
    // Utilise ConcurrentHashMap.newKeySet() pour éviter les ConcurrentModificationException
    private static final Set<String> readyVotes = ConcurrentHashMap.newKeySet();

    /**
     * Exécute la commande /ready
     */
    public Response Command(TokenSessionBO tokenSessionBO, ParameterBO parameterBO, PersonaEntity personaEntity,
                            LobbyDAO lobbyDAO, OpenFireSoapBoxCli openFireSoapBoxCli, LobbyCountdownBO lobbyCountdownBO) {
        
        // Vérifier si la fonctionnalité est activée
        if (!parameterBO.getBoolParam("SBRWR_ENABLE_LOBBY_READY")) {
            openFireSoapBoxCli.send(
                XmppChat.createSystemMessage("SBRWR_READY_WARNING_DISABLED"),
                personaEntity.getPersonaId()
            );
            return Response.noContent().build();
        }

        TokenSessionEntity tokendata = tokenSessionBO.findByUserId(personaEntity.getUser().getId());
        
        if (tokendata == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("can't find valid token for user").build();
        }

        Long getActiveLobbyId = (tokendata.getActiveLobbyId() != null) ? tokendata.getActiveLobbyId() : 0L;
        Long getEventSessionId = (tokendata.getEventSessionId() != null) ? tokendata.getEventSessionId() : 0L;

        // Le joueur doit être dans un lobby mais pas dans une course
        if (getActiveLobbyId != 0L && getEventSessionId == 0L) {
            LobbyEntity lobbyEntity = lobbyDAO.findById(getActiveLobbyId);
            
            if (lobbyEntity == null) {
                openFireSoapBoxCli.send(
                    XmppChat.createSystemMessage("SBRWR_READY_WARNING_ONFREEROAM"),
                    personaEntity.getPersonaId()
                );
                return Response.noContent().build();
            }

            // Récupérer le nombre de joueurs dans le lobby
            List<LobbyEntrantEntity> lobbyEntrants = lobbyEntity.getEntrants();
            int totalUsersInLobby = (lobbyEntrants == null) ? 1 : lobbyEntrants.size();

            // Il faut au moins 2 joueurs pour utiliser /ready
            if (totalUsersInLobby < 2) {
                openFireSoapBoxCli.send(
                    XmppChat.createSystemMessage("SBRWR_READY_WARNING_ALONE"),
                    personaEntity.getPersonaId()
                );
                return Response.noContent().build();
            }

            // Vérifier que le countdown n'est pas déjà à 5 secondes ou moins
            int remainingTime = lobbyEntity.getLobbyCountdownInMilliseconds(lobbyEntity.getEvent().getLobbyCountdownTime());
            if (remainingTime <= 5000) {
                openFireSoapBoxCli.send(
                    XmppChat.createSystemMessage("SBRWR_READY_WARNING_TOOLATE"),
                    personaEntity.getPersonaId()
                );
                return Response.noContent().build();
            }

            // Créer la clé unique pour ce vote (lobbyId_personaId)
            String voteKey = getActiveLobbyId + "_" + personaEntity.getPersonaId();

            // Vérifier si le joueur a déjà voté
            if (readyVotes.contains(voteKey)) {
                openFireSoapBoxCli.send(
                    XmppChat.createSystemMessage("SBRWR_READY_WARNING_ALREADYVOTED"),
                    personaEntity.getPersonaId()
                );
                return Response.noContent().build();
            }

            // Enregistrer le vote
            readyVotes.add(voteKey);

            // Compter le nombre total de votes pour ce lobby
            int totalVotes = 0;
            for (String key : readyVotes) {
                if (key.startsWith(getActiveLobbyId + "_")) {
                    totalVotes++;
                }
            }

            // Informer le joueur que son vote a été enregistré
            String voterMessage = "SBRWR_READY_VOTEREGISTERED," + totalVotes + "," + totalUsersInLobby;
            openFireSoapBoxCli.send(
                XmppChat.createSystemMessage(voterMessage),
                personaEntity.getPersonaId()
            );

            // Informer les autres joueurs (optionnel, configurable)
            if (parameterBO.getBoolParam("SBRWR_READY_ENABLE_VOTEMESSAGES")) {
                for (LobbyEntrantEntity lobbyEntrant : lobbyEntrants) {
                    if (!lobbyEntrant.getPersona().getPersonaId().equals(personaEntity.getPersonaId())) {
                        String broadcastMsg = "SBRWR_READY_USERVOTED," + personaEntity.getName() + "," + totalVotes + "," + totalUsersInLobby;
                        openFireSoapBoxCli.send(
                            XmppChat.createSystemMessage(broadcastMsg),
                            lobbyEntrant.getPersona().getPersonaId()
                        );
                    }
                }
            }

            // Vérifier si le seuil est atteint (60% par défaut)
            int requiredPercentage = parameterBO.getIntParam("SBRWR_READY_THRESHOLD");
            double votePercentage = ((double) totalVotes / totalUsersInLobby) * 100.0;
            
            if (votePercentage >= requiredPercentage) {
                // Démarrer le countdown à 5 secondes
                lobbyCountdownBO.scheduleLobbyStart(lobbyEntity, 5000);

                // Envoyer le nouveau countdown à tous les joueurs
                LobbyCountdown lobbyCountdown = new LobbyCountdown();
                lobbyCountdown.setLobbyId(lobbyEntity.getId());
                lobbyCountdown.setEventId(lobbyEntity.getEvent().getId());
                lobbyCountdown.setLobbyStuckDurationInMilliseconds(10000);
                lobbyCountdown.setIsWaiting(false);
                lobbyCountdown.setLobbyCountdownInMilliseconds(5000);
                
                XMPP_ResponseTypeLobbyCountdown response = new XMPP_ResponseTypeLobbyCountdown();
                response.setLobbyCountdown(lobbyCountdown);
                
                for (LobbyEntrantEntity lobbyEntrant : lobbyEntrants) {
                    openFireSoapBoxCli.send(response, lobbyEntrant.getPersona().getPersonaId());
                }

                // Nettoyer tous les votes de ce lobby
                readyVotes.removeIf(key -> key.startsWith(getActiveLobbyId + "_"));

                // Informer tous les joueurs que le countdown a été réduit
                String successMessage = "SBRWR_READY_SUCCESS," + totalVotes + "," + totalUsersInLobby;
                for (LobbyEntrantEntity lobbyEntrant : lobbyEntrants) {
                    openFireSoapBoxCli.send(
                        XmppChat.createSystemMessage(successMessage),
                        lobbyEntrant.getPersona().getPersonaId()
                    );
                }
            }
            
        } else if (getActiveLobbyId != 0L && getEventSessionId != 0L) {
            // Le joueur est dans une course
            openFireSoapBoxCli.send(
                XmppChat.createSystemMessage("SBRWR_READY_WARNING_ONEVENT"),
                personaEntity.getPersonaId()
            );
        } else {
            // Le joueur est en freeroam
            openFireSoapBoxCli.send(
                XmppChat.createSystemMessage("SBRWR_READY_WARNING_ONFREEROAM"),
                personaEntity.getPersonaId()
            );
        }

        return Response.noContent().build();
    }

    /**
     * Nettoie les votes d'un lobby spécifique (appelé quand un lobby démarre ou est fermé)
     */
    public static void clearLobbyVotes(Long lobbyId) {
        readyVotes.removeIf(key -> key.startsWith(lobbyId + "_"));
    }
}
