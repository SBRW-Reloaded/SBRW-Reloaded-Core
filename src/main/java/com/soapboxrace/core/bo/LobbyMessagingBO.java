package com.soapboxrace.core.bo;

import com.soapboxrace.core.jpa.LobbyEntity;
import com.soapboxrace.core.jpa.PersonaEntity;
import com.soapboxrace.core.xmpp.OpenFireSoapBoxCli;
import com.soapboxrace.jaxb.http.LobbyEntrantAdded;
import com.soapboxrace.jaxb.http.LobbyEntrantInfo;
import com.soapboxrace.jaxb.http.LobbyEntrantRemoved;
import com.soapboxrace.jaxb.xmpp.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Sends XMPP messages related to lobbies.
 *
 * @author coder
 */
@ApplicationScoped
@Transactional
public class LobbyMessagingBO {
    private static final Logger logger = LoggerFactory.getLogger(LobbyMessagingBO.class);
    
    @Inject
    private OpenFireSoapBoxCli openFireSoapBoxCli;

    @Inject
    private TokenSessionBO tokenSessionBO;

    /**
     * Prepares and sends a new {@link com.soapboxrace.jaxb.http.LobbyEntrantAdded} message
     * to the given recipient {@link com.soapboxrace.core.jpa.PersonaEntity}.
     *
     * @param lobbyEntity      The {@link LobbyEntity} attached to the message.
     * @param sourcePersona    The {@link PersonaEntity} attached to the message.
     * @param recipientPersona The {@link PersonaEntity} receiving the message.
     * @throws IllegalArgumentException if the source and recipient persona IDs are the same
     */
    public void sendJoinMessage(LobbyEntity lobbyEntity, PersonaEntity sourcePersona, PersonaEntity recipientPersona) {
        if (sourcePersona.getPersonaId().equals(recipientPersona.getPersonaId())) {
            throw new IllegalArgumentException("Source and recipient personas cannot be the same!");
        }

        LobbyEntrantAdded lobbyEntrantAdded = new LobbyEntrantAdded();
        lobbyEntrantAdded.setHeat(1);
        lobbyEntrantAdded.setLevel(sourcePersona.getLevel());
        lobbyEntrantAdded.setPersonaId(sourcePersona.getPersonaId());
        lobbyEntrantAdded.setLobbyId(lobbyEntity.getId());

        XMPP_ResponseTypeEntrantAdded response = new XMPP_ResponseTypeEntrantAdded();
        response.setLobbyInvite(lobbyEntrantAdded);

        openFireSoapBoxCli.send(response, recipientPersona.getPersonaId());
    }

    /**
     * Prepares and sends a new {@link com.soapboxrace.jaxb.http.LobbyEntrantRemoved} message
     * to the given recipient {@link com.soapboxrace.core.jpa.PersonaEntity}.
     *
     * @param lobbyEntity      The {@link LobbyEntity} attached to the message.
     * @param sourcePersona    The {@link PersonaEntity} attached to the message.
     * @param recipientPersona The {@link PersonaEntity} receiving the message.
     * @throws IllegalArgumentException if the source and recipient persona IDs are the same
     */
    public void sendLeaveMessage(LobbyEntity lobbyEntity, PersonaEntity sourcePersona, PersonaEntity recipientPersona) {
        LobbyEntrantRemoved lobbyEntrantRemoved = new LobbyEntrantRemoved();
        lobbyEntrantRemoved.setLobbyId(lobbyEntity.getId());
        lobbyEntrantRemoved.setPersonaId(sourcePersona.getPersonaId());

        XMPP_ResponseTypeEntrantRemoved response = new XMPP_ResponseTypeEntrantRemoved();
        response.setLobbyEntrantRemoved(lobbyEntrantRemoved);

        openFireSoapBoxCli.send(response, recipientPersona.getPersonaId());
    }

    /**
     * Prepares and sends a new {@link com.soapboxrace.jaxb.xmpp.XMPP_LobbyInviteType} message
     * to the given recipient {@link com.soapboxrace.core.jpa.PersonaEntity}.
     *
     * @param lobbyEntity      The {@link LobbyEntity} attached to the message.
     * @param recipientPersona The {@link PersonaEntity} receiving the message.
     * @param inviteLifetime   The lifetime of the invitation in milliseconds.
     */
    public void sendLobbyInvitation(LobbyEntity lobbyEntity, PersonaEntity recipientPersona, long inviteLifetime) {
        try {
            // Ne pas envoyer d'invitations aux joueurs en course ou sur l'écran de récompenses
            if (recipientPersona.getUser() != null) {
                Long userId = recipientPersona.getUser().getId();
                if (tokenSessionBO.isPlayerInEvent(userId)) {
                    logger.debug("Skipping lobby invitation for PersonaId={} - player is in event/rewards screen", 
                        recipientPersona.getPersonaId());
                    return;
                }
            }
            
            XMPP_LobbyInviteType lobbyInvite = new XMPP_LobbyInviteType();
            lobbyInvite.setEventId(lobbyEntity.getEvent().getId());
            lobbyInvite.setLobbyInviteId(lobbyEntity.getId());

            if (!lobbyEntity.getPersonaId().equals(recipientPersona.getPersonaId())) {
                lobbyInvite.setInvitedByPersonaId(lobbyEntity.getPersonaId());
                lobbyInvite.setInviteLifetimeInMilliseconds(inviteLifetime);
                lobbyInvite.setPrivate(lobbyEntity.getIsPrivate());
            }

            XMPP_ResponseTypeLobbyInvite response = new XMPP_ResponseTypeLobbyInvite();
            response.setLobbyInvite(lobbyInvite);

            openFireSoapBoxCli.send(response, recipientPersona.getPersonaId());
            
            logger.debug("Lobby invitation sent to PersonaId={} for LobbyId={}", 
                        recipientPersona.getPersonaId(), lobbyEntity.getId());
        } catch (Exception e) {
            logger.error(String.format("Failed to send lobby invitation to PersonaId=%d: %s",
                    recipientPersona.getPersonaId(), e.getMessage()), e);
        }
    }

    public void sendRelay(XMPP_LobbyLaunchedType lobbyLaunched, XMPP_CryptoTicketsType xMPP_CryptoTicketsType) {
        xMPP_CryptoTicketsType.getP2PCryptoTicket().sort(
                Comparator.comparing(XMPP_P2PCryptoTicketType::getPersonaId).reversed());

        List<LobbyEntrantInfo> lobbyEntrantInfo = lobbyLaunched.getEntrants().getLobbyEntrantInfo();
        logger.info("RELAY_LAUNCH: Sending launch messages to {} players for lobby {}", 
            lobbyEntrantInfo.size(), lobbyLaunched.getLobbyId());
        
        for (LobbyEntrantInfo lobbyEntrantInfoType : lobbyEntrantInfo) {
            long personaId = lobbyEntrantInfoType.getPersonaId();
            XMPP_CryptoTicketsType cryptoTicketsTypeTmp = new XMPP_CryptoTicketsType();
            List<XMPP_P2PCryptoTicketType> p2pCryptoTicket = xMPP_CryptoTicketsType.getP2PCryptoTicket();
            for (XMPP_P2PCryptoTicketType p2pCryptoTicketType : p2pCryptoTicket) {
                if (personaId != p2pCryptoTicketType.getPersonaId()) {
                    cryptoTicketsTypeTmp.getP2PCryptoTicket().add(p2pCryptoTicketType);
                }
            }
            String udpRaceHostIp = lobbyEntrantInfoType.getUdpRaceHostIp();
            if (udpRaceHostIp != null) {
                lobbyLaunched.setUdpRelayHost(udpRaceHostIp);
            }
            lobbyLaunched.setCryptoTickets(cryptoTicketsTypeTmp);

            // Send entrants with "self" last, others sorted descending by personaId.
            List<LobbyEntrantInfo> customizedEntrants = new ArrayList<>(lobbyEntrantInfo);
            customizedEntrants.sort(
                    Comparator.<LobbyEntrantInfo, Boolean>comparing(
                                    l -> l.getPersonaId() == personaId)
                            .thenComparing(Comparator.comparing(LobbyEntrantInfo::getPersonaId).reversed()));
            com.soapboxrace.jaxb.http.Entrants customizedEntrantsWrapper = new com.soapboxrace.jaxb.http.Entrants();
            customizedEntrantsWrapper.getLobbyEntrantInfo().addAll(customizedEntrants);
            lobbyLaunched.setEntrants(customizedEntrantsWrapper);

            XMPP_ResponseTypeLobbyLaunched responseType = new XMPP_ResponseTypeLobbyLaunched();
            responseType.setLobbyInvite(lobbyLaunched);
            openFireSoapBoxCli.send(responseType, personaId);
            
            logger.info("RELAY_LAUNCH: Sent launch message to PersonaId={} for lobby {}", 
                personaId, lobbyLaunched.getLobbyId());
        }
    }
}
