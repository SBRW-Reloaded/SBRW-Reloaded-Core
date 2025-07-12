/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.bo;

import com.soapboxrace.core.dao.PersonaDAO;
import com.soapboxrace.core.dao.SocialRelationshipDAO;
import com.soapboxrace.core.engine.EngineException;
import com.soapboxrace.core.engine.EngineExceptionCode;
import com.soapboxrace.core.events.PersonaPresenceUpdated;
import com.soapboxrace.core.jpa.PersonaEntity;
import com.soapboxrace.core.jpa.SocialRelationshipEntity;
import com.soapboxrace.core.xmpp.OpenFireSoapBoxCli;
import com.soapboxrace.jaxb.http.*;
import com.soapboxrace.jaxb.xmpp.XMPP_FriendPersonaType;
import com.soapboxrace.jaxb.xmpp.XMPP_ResponseTypePersonaBase;
import org.slf4j.Logger;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.ejb.*;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.util.List;
import java.util.Objects;

@Singleton
@Startup
@Lock(LockType.READ)
public class SocialRelationshipBO {
    @EJB
    private SocialRelationshipDAO socialRelationshipDAO;
    @EJB
    private PersonaDAO personaDAO;
    @EJB
    private OpenFireSoapBoxCli openFireSoapBoxCli;
    @EJB
    private DriverPersonaBO driverPersonaBO;
    @EJB
    private PresenceBO presenceBO;
    @Inject
    private Logger logger;

    @PostConstruct
    public void init() {
        logger.info("Initialized social relationship system");
    }

    @PreDestroy
    public void shutdown() {
        logger.info("Shutdown social relationship system");
    }

    @Asynchronous
    public void handlePersonaPresenceUpdated(@Observes PersonaPresenceUpdated personaPresenceUpdated) {
        Long personaPresence = personaPresenceUpdated.getPresence();
        PersonaEntity personaEntity = personaDAO.find(personaPresenceUpdated.getPersonaId());
        
        if (personaEntity != null) {
            logger.debug("Handling presence update for persona {}: {}", personaEntity.getName(), personaPresence);
            
            // AMÉLIORATION : Ajouter un délai pour éviter les race conditions lors de la connexion
            try {
                Thread.sleep(100); // Petit délai pour laisser la connexion se stabiliser
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // Vérifier que la présence est toujours valide après le délai
            Long currentPresence = presenceBO.getPresence(personaEntity.getPersonaId());
            if (currentPresence.equals(personaPresence)) {
                sendPresencePacketsWithRetry(personaEntity, personaPresence);
            } else {
                logger.debug("Presence changed during processing for persona {}, using current: {}", 
                           personaEntity.getName(), currentPresence);
                sendPresencePacketsWithRetry(personaEntity, currentPresence);
            }
        } else {
            logger.warn("Cannot handle presence update for persona {}: persona not found", personaPresenceUpdated.getPersonaId());
        }
    }
    
    /**
     * AMÉLIORATION: Envoi de paquets de présence avec retry en cas d'erreur
     */
    private void sendPresencePacketsWithRetry(PersonaEntity personaEntity, Long presence) {
        int maxRetries = 3;
        int retryCount = 0;
        
        while (retryCount < maxRetries) {
            try {
                sendPresencePackets(personaEntity, presence);
                return; // Succès, sortir de la boucle
            } catch (Exception e) {
                retryCount++;
                logger.warn("Error sending presence packets for persona {} (attempt {}/{}): {}", 
                          personaEntity.getName(), retryCount, maxRetries, e.getMessage());
                
                if (retryCount < maxRetries) {
                    try {
                        Thread.sleep(50 * retryCount); // Délai exponentiel
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        
        logger.error("Failed to send presence packets for persona {} after {} attempts", 
                    personaEntity.getName(), maxRetries);
    }

    public PersonaFriendsList getFriendsList(Long userId) {
        PersonaFriendsList personaFriendsList = new PersonaFriendsList();
        personaFriendsList.setFriendPersona(new ArrayOfFriendPersona());

        List<SocialRelationshipEntity> socialRelationshipEntityList = this.socialRelationshipDAO.findByUserId(userId);

        for (SocialRelationshipEntity socialRelationshipEntity : socialRelationshipEntityList) {
            if (socialRelationshipEntity.getStatus() == 0) {
                this.addPendingRequestToList(personaFriendsList, socialRelationshipEntity);
            } else if (socialRelationshipEntity.getStatus() == 1) {
                this.addFriendToList(personaFriendsList, socialRelationshipEntity);
            }
        }

        return personaFriendsList;
    }

    public ArrayOfBasicBlockPlayerInfo getBlockedUserList(Long userId) {
        List<SocialRelationshipEntity> socialRelationshipEntityList =
                this.socialRelationshipDAO.findByUserIdAndStatus(userId, 2L);
        ArrayOfBasicBlockPlayerInfo arrayOfBasicBlockPlayerInfo = new ArrayOfBasicBlockPlayerInfo();

        for (SocialRelationshipEntity socialRelationshipEntity : socialRelationshipEntityList) {
            this.addBlockedUserToList(arrayOfBasicBlockPlayerInfo, socialRelationshipEntity);
        }

        return arrayOfBasicBlockPlayerInfo;
    }

    public ArrayOfLong getBlockersByUsers(Long personaId) {
        PersonaEntity personaEntity = personaDAO.find(personaId);

        if (personaEntity == null) {
            throw new EngineException(EngineExceptionCode.RemotePersonaIdInvalid, false);
        }

        ArrayOfLong arrayOfLong = new ArrayOfLong();

        for (SocialRelationshipEntity socialRelationshipEntity :
                this.socialRelationshipDAO.findByRemoteUserIdAndStatus(personaEntity.getUser().getId(), 2L)) {
            arrayOfLong.getLong().add(socialRelationshipEntity.getUser().getId());
        }

        return arrayOfLong;
    }

    public FriendResult addFriend(Long activePersonaId, String displayName) {
        // activePersonaEntity is the SENDER, targetPersonaEntity is the RECIPIENT.
        PersonaEntity activePersonaEntity = personaDAO.find(activePersonaId);
        PersonaEntity targetPersonaEntity = personaDAO.findByName(displayName);
        FriendResult friendResult = new FriendResult();

        // This shouldn't happen, but we have to catch it anyway.
        if (activePersonaId == 0L || activePersonaEntity == null) {
            throw new EngineException(EngineExceptionCode.FailedSessionSecurityPolicy, false);
        }

        // Sanity check
        if (targetPersonaEntity == null) {
            friendResult.setResult(FriendResultStatus.CannotFindDriver);
            return friendResult;
        }

        // I know SOMEONE will try this. It's inevitable.
        if (targetPersonaEntity.getPersonaId().equals(activePersonaId)) {
            friendResult.setResult(FriendResultStatus.CannotAddSelf);
            return friendResult;
        }

        // Look for an existing relationship between the active user and the target user.
        // Ideally, there won't be one.
        // Scoped block is used to avoid accidental usage of the temporary variable.
        {
            SocialRelationshipEntity socialRelationshipEntity =
                    this.socialRelationshipDAO.findByLocalAndRemoteUser(activePersonaEntity.getUser().getId(),
                            targetPersonaEntity.getUser().getId());

            if (socialRelationshipEntity != null && socialRelationshipEntity.getStatus() != 3L) {
                // Here, we're assuming that the players are, in fact, already friends.
                // Of course, this should be the case for any ordinary player, who probably
                // isn't messing around with the game.
                // It should be safe to assume that a driver won't try to add
                // a driver they've blocked, as a friend.
                friendResult.setResult(FriendResultStatus.AlreadyFriends);
                return friendResult;
            }
        }

        {
            SocialRelationshipEntity socialRelationshipEntity =
                    this.socialRelationshipDAO.findByLocalAndRemoteUser(targetPersonaEntity.getUser().getId(),
                            activePersonaEntity.getUser().getId());

            if (socialRelationshipEntity != null) {
                // check to see if target has blocked active player
                if (socialRelationshipEntity.getStatus() == 2L) {
                    // no dedicated exception code for this, so we just use SocialFriendRequestNotResolvable. game is
                    // fine with EngineExceptions.
                    throw new EngineException(EngineExceptionCode.SocialFriendRequestNotResolvable, false);
                } else {
                    friendResult.setResult(FriendResultStatus.AlreadyFriends);
                    return friendResult;
                }
            }
        }
        // Create remote relationship
        createNewRelationship(targetPersonaEntity, activePersonaEntity, 0L);
        // Create local relationship
        createNewRelationship(activePersonaEntity, targetPersonaEntity, 3L);

        sendFriendPersonaPacket(activePersonaEntity, targetPersonaEntity.getPersonaId());

        friendResult.setResult(FriendResultStatus.Success);
        friendResult.setFriendPersona(copyPersonaEntityToFriendPersona(targetPersonaEntity));

        return friendResult;
    }

    public PersonaBase resolveFriendsRequest(Long activePersonaId, Long friendPersonaId, int resolution) {
        if (activePersonaId == 0L) {
            throw new EngineException(EngineExceptionCode.FailedSessionSecurityPolicy, false);
        }

        if (friendPersonaId == 0L) {
            throw new EngineException(EngineExceptionCode.RemotePersonaIdInvalid, false);
        }

        PersonaEntity activePersonaEntity = personaDAO.find(activePersonaId);

        if (activePersonaEntity == null) {
            throw new EngineException(EngineExceptionCode.RemotePersonaIdInvalid, false);
        }

        PersonaEntity friendPersonaEntity = personaDAO.find(friendPersonaId);

        if (friendPersonaEntity == null) {
            throw new EngineException(EngineExceptionCode.RemotePersonaIdInvalid, false);
        }

        SocialRelationshipEntity socialRelationshipEntity =
                socialRelationshipDAO.findByLocalAndRemoteUser(activePersonaEntity.getUser().getId(),
                        friendPersonaEntity.getUser().getId());
        SocialRelationshipEntity pendingSocialRelationshipEntity =
                socialRelationshipDAO.findByLocalAndRemoteUser(friendPersonaEntity.getUser().getId(),
                        activePersonaEntity.getUser().getId());

        if (socialRelationshipEntity == null || pendingSocialRelationshipEntity == null) {
            throw new EngineException(EngineExceptionCode.SocialFriendRequestNotResolvable, false);
        }

        switch (resolution) {
            case 0: // reject
                socialRelationshipDAO.delete(socialRelationshipEntity);
                socialRelationshipDAO.delete(pendingSocialRelationshipEntity);
                this.sendPresencePacket(activePersonaEntity, 0L, friendPersonaId);
                return null;
            case 1: // accept
                this.sendPresencePacket(activePersonaEntity, presenceBO.getPresence(activePersonaId), friendPersonaId);
                pendingSocialRelationshipEntity.setStatus(1L);
                socialRelationshipEntity.setStatus(1L);

                socialRelationshipDAO.update(pendingSocialRelationshipEntity);
                socialRelationshipDAO.update(socialRelationshipEntity);

                return driverPersonaBO.getPersonaBase(friendPersonaEntity);
            default:
                throw new EngineException(EngineExceptionCode.SocialFriendRequestNotResolvable, false);
        }
    }

    public PersonaBase removeFriend(Long activePersonaId, Long friendPersonaId) {
        PersonaEntity activePersonaEntity = personaDAO.find(activePersonaId);

        if (activePersonaEntity == null) {
            throw new EngineException(EngineExceptionCode.FailedSessionSecurityPolicy, false);
        }

        PersonaEntity friendPersonaEntity = personaDAO.find(friendPersonaId);

        if (friendPersonaEntity == null) {
            throw new EngineException(EngineExceptionCode.RemotePersonaIdInvalid, false);
        }

        // Possible cases:
        // - driver sends friend request and then cancels it: just delete the remote relationship
        // - one side of a friendship unfriends the other side - delete both sides
        // DO NOT MESS WITH BLOCKS!
        SocialRelationshipEntity activeSide =
                socialRelationshipDAO.findByLocalAndRemoteUser(activePersonaEntity.getUser().getId(),
                        friendPersonaEntity.getUser().getId());
        SocialRelationshipEntity remoteSide =
                socialRelationshipDAO.findByLocalAndRemoteUser(friendPersonaEntity.getUser().getId(),
                        activePersonaEntity.getUser().getId());
        if (remoteSide == null || activeSide == null) {
            // for lack of a better exception code, we just use the "not resolvable" error... they're all the same
            // to the game.
            throw new EngineException(EngineExceptionCode.SocialFriendRequestNotResolvable, false);
        }

        if (remoteSide.getStatus() == 2L || activeSide.getStatus() == 2L) {
            // something like this shouldn't ever happen, but... better to be safe than sorry
            throw new EngineException(EngineExceptionCode.SocialFriendRequestNotResolvable, false);
        }

        socialRelationshipDAO.delete(activeSide);
        socialRelationshipDAO.delete(remoteSide);

        return driverPersonaBO.getPersonaBase(activePersonaEntity);
    }

    public PersonaBase blockPlayer(Long userId, Long activePersonaId, Long otherPersonaId) {
        PersonaEntity activePersonaEntity = personaDAO.find(activePersonaId);

        if (activePersonaEntity == null) {
            throw new EngineException(EngineExceptionCode.FailedSessionSecurityPolicy, false);
        }

        PersonaEntity otherPersonaEntity = personaDAO.find(otherPersonaId);

        if (otherPersonaEntity == null) {
            throw new EngineException(EngineExceptionCode.RemotePersonaIdInvalid, false);
        }

        SocialRelationshipEntity localSide = socialRelationshipDAO.findByLocalAndRemoteUser(userId,
                otherPersonaEntity.getUser().getId());
        SocialRelationshipEntity remoteSide =
                socialRelationshipDAO.findByLocalAndRemoteUser(otherPersonaEntity.getUser().getId(),
                        userId);

        if (localSide == null) {
            createNewRelationship(activePersonaEntity, otherPersonaEntity, 2L);
        } else {
            localSide.setStatus(2L);
            socialRelationshipDAO.update(localSide);
        }

        if (remoteSide != null) {
            socialRelationshipDAO.delete(remoteSide);
            sendPresencePacket(activePersonaEntity, 0L, otherPersonaId);
        }

        return driverPersonaBO.getPersonaBase(otherPersonaEntity);
    }

    public PersonaBase unblockPlayer(Long userId, Long otherPersonaId) {
        PersonaEntity otherPersonaEntity = personaDAO.find(otherPersonaId);

        if (otherPersonaEntity == null) {
            throw new EngineException(EngineExceptionCode.RemotePersonaIdInvalid, false);
        }

        SocialRelationshipEntity localSide = socialRelationshipDAO.findByLocalAndRemoteUser(userId,
                otherPersonaEntity.getUser().getId());

        if (localSide != null && localSide.getStatus() == 2L) {
            socialRelationshipDAO.delete(localSide);
        } else {
            throw new EngineException(EngineExceptionCode.SocialFriendRequestNotResolvable, false);
        }

        return driverPersonaBO.getPersonaBase(otherPersonaEntity);
    }

    private void createNewRelationship(PersonaEntity localPersona, PersonaEntity remotePersona,
                                       long status) {
        SocialRelationshipEntity socialRelationshipEntity = new SocialRelationshipEntity();
        socialRelationshipEntity.setRemotePersonaId(remotePersona.getPersonaId());
        socialRelationshipEntity.setStatus(status);
        socialRelationshipEntity.setUser(localPersona.getUser());
        socialRelationshipEntity.setRemoteUser(remotePersona.getUser());
        socialRelationshipDAO.insert(socialRelationshipEntity);
    }

    private void addBlockedUserToList(ArrayOfBasicBlockPlayerInfo arrayOfBasicBlockPlayerInfo,
                                      SocialRelationshipEntity socialRelationshipEntity) {
        for (PersonaEntity personaEntity : socialRelationshipEntity.getRemoteUser().getPersonas()) {
            BasicBlockPlayerInfo basicBlockPlayerInfo = new BasicBlockPlayerInfo();

            basicBlockPlayerInfo.setPersonaId(personaEntity.getPersonaId());
            basicBlockPlayerInfo.setUserId(socialRelationshipEntity.getRemoteUser().getId());

            arrayOfBasicBlockPlayerInfo.getBasicBlockPlayerInfo().add(basicBlockPlayerInfo);
        }
    }

    private void addFriendToList(PersonaFriendsList personaFriendsList,
                                 SocialRelationshipEntity socialRelationshipEntity) {
        for (PersonaEntity personaEntity : socialRelationshipEntity.getRemoteUser().getPersonas()) {
            // AMÉLIORATION : Filtrer les personas supprimés
            if (personaEntity.getDeletedAt() != null) {
                logger.debug("Skipping deleted persona {} from friend list", personaEntity.getName());
                continue;
            }
            
            FriendPersona friendPersona = copyPersonaEntityToFriendPersona(personaEntity);
            Long presence = getValidatedPresence(personaEntity.getPersonaId());
            friendPersona.setPresence(presence);
            
            logger.debug("Added friend {} to list with validated presence {}", personaEntity.getName(), presence);
            personaFriendsList.getFriendPersona().getFriendPersona().add(friendPersona);
        }
    }
    
    /**
     * AMÉLIORATION: Validation des présences avec gestion des erreurs
     * Cette méthode valide et rafraîchit les présences pour éviter les incohérences
     */
    private Long getValidatedPresence(Long personaId) {
        try {
            Long presence = presenceBO.getPresence(personaId);
            
            if (presence == null) {
                logger.debug("Presence null for persona {}, returning offline", personaId);
                return PresenceBO.PRESENCE_OFFLINE;
            }
            
            // Rafraîchir la présence pour maintenir la cohérence
            if (presence.equals(PresenceBO.PRESENCE_ONLINE) || presence.equals(PresenceBO.PRESENCE_IN_RACE)) {
                presenceBO.refreshPresence(personaId);
                logger.debug("Refreshed presence for persona {} to maintain consistency", personaId);
            }
            
            return presence;
            
        } catch (Exception e) {
            logger.warn("Error getting presence for persona {}: {}", personaId, e.getMessage());
            return PresenceBO.PRESENCE_OFFLINE;
        }
    }

    private void addPendingRequestToList(PersonaFriendsList personaFriendsList,
                                         SocialRelationshipEntity socialRelationshipEntity) {
        FriendPersona friendPersona =
                copyPersonaEntityToFriendPersona(personaDAO.find(socialRelationshipEntity.getRemotePersonaId()));
        friendPersona.setPresence(3L);
        personaFriendsList.getFriendPersona().getFriendPersona().add(friendPersona);
    }

    private FriendPersona copyPersonaEntityToFriendPersona(PersonaEntity personaEntity) {
        Objects.requireNonNull(personaEntity, "personaEntity is null!");
        FriendPersona friendPersona = new FriendPersona();
        friendPersona.setPersonaId(personaEntity.getPersonaId());
        friendPersona.setName(personaEntity.getName());
        friendPersona.setOriginalName("test");
        friendPersona.setSocialNetwork(0);
        friendPersona.setUserId(personaEntity.getUser().getId());
        friendPersona.setLevel(personaEntity.getLevel());
        friendPersona.setIconIndex(personaEntity.getIconIndex());

        return friendPersona;
    }

    private void sendPresencePackets(PersonaEntity personaEntity, Long presence) {
        Objects.requireNonNull(personaEntity, "personaEntity is null!");
        
        logger.debug("Sending presence packets for persona {} with presence {}", personaEntity.getName(), presence);
        
        // Trouver toutes les relations où ce persona est l'ami distant (les personnes qui ont ce persona comme ami)
        List<SocialRelationshipEntity> friendsWhoHaveThisPersona = socialRelationshipDAO.findByRemotePersonaId(personaEntity.getPersonaId());
        
        // Envoyer la mise à jour de présence seulement aux personnes qui ont ce persona comme ami
        for (SocialRelationshipEntity relationship : friendsWhoHaveThisPersona) {
            if (relationship.getStatus() == 1) { // Seulement les amis confirmés
                try {
                    // Obtenir le premier persona de l'utilisateur ami
                    if (relationship.getUser() != null && !relationship.getUser().getPersonas().isEmpty()) {
                        Long friendPersonaId = relationship.getUser().getPersonas().get(0).getPersonaId();
                        sendPresencePacket(personaEntity, presence, friendPersonaId);
                        logger.debug("Sent presence update for {} to friend {}", personaEntity.getName(), friendPersonaId);
                    }
                } catch (Exception e) {
                    logger.error("Error sending presence packet for persona {} to friend {}: {}", 
                               personaEntity.getName(), relationship.getUser().getId(), e.getMessage());
                }
            }
        }
        
        // Également envoyer aux amis du joueur qui se connecte (relations bidirectionnelles)
        List<SocialRelationshipEntity> thisPersonaFriends = socialRelationshipDAO.findByUserIdAndStatus(personaEntity.getUser().getId(), 1L);
        for (SocialRelationshipEntity relationship : thisPersonaFriends) {
            try {
                sendPresencePacket(personaEntity, presence, relationship.getRemotePersonaId());
                logger.debug("Sent presence update for {} to their friend {}", personaEntity.getName(), relationship.getRemotePersonaId());
            } catch (Exception e) {
                logger.error("Error sending presence packet for persona {} to their friend {}: {}", 
                           personaEntity.getName(), relationship.getRemotePersonaId(), e.getMessage());
            }
        }
    }

    private void sendPresencePacket(PersonaEntity personaEntity, Long presence,
                                    Long targetPersonaId) {
        XMPP_ResponseTypePersonaBase personaPacket = new XMPP_ResponseTypePersonaBase();
        PersonaBase xmppPersonaBase = driverPersonaBO.getPersonaBase(personaEntity);
        xmppPersonaBase.setPresence(presence);
        personaPacket.setPersonaBase(xmppPersonaBase);

        openFireSoapBoxCli.send(personaPacket, targetPersonaId);
    }

    /**
     * AMÉLIORATION: Nettoyage périodique des présences des amis
     * Cette méthode nettoie les présences incohérentes et actualise les listes d'amis
     */
    @Schedule(minute = "*/5", hour = "*", persistent = false)
    public void cleanupFriendsPresences() {
        logger.debug("Starting friends presence cleanup task");
        
        try {
            // Récupérer toutes les relations d'amitié actives
            List<SocialRelationshipEntity> friendships = socialRelationshipDAO.findByStatus(1L);
            
            int refreshedCount = 0;
            int errorCount = 0;
            
            for (SocialRelationshipEntity friendship : friendships) {
                try {
                    // Vérifier et rafraîchir les présences des amis
                    if (friendship.getRemoteUser() != null && !friendship.getRemoteUser().getPersonas().isEmpty()) {
                        for (PersonaEntity persona : friendship.getRemoteUser().getPersonas()) {
                            if (persona.getDeletedAt() == null) {
                                // Utiliser la méthode sécurisée qui ne recrée pas de présence
                                if (presenceBO.refreshPresenceIfExists(persona.getPersonaId())) {
                                    refreshedCount++;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Error refreshing presence for friendship {}: {}", friendship.getId(), e.getMessage());
                    errorCount++;
                }
            }
            
            logger.debug("Friends presence cleanup completed: {} refreshed, {} errors", refreshedCount, errorCount);
            
        } catch (Exception e) {
            logger.error("Error during friends presence cleanup", e);
        }
    }

    private void sendFriendPersonaPacket(PersonaEntity personaEntity, Long targetPersonaId) {
        XMPP_FriendPersonaType personaPacket = new XMPP_FriendPersonaType();
        PersonaBase xmppPersonaBase = driverPersonaBO.getPersonaBase(personaEntity);
        xmppPersonaBase.setPresence(3L);

        // copy to XMPP_FriendPersonaType
        personaPacket.setIconIndex(xmppPersonaBase.getIconIndex());
        personaPacket.setLevel(xmppPersonaBase.getLevel());
        personaPacket.setPresence(xmppPersonaBase.getPresence());
        personaPacket.setName(xmppPersonaBase.getName());
        personaPacket.setOriginalName("test");
        personaPacket.setSocialNetwork(0);
        personaPacket.setUserId(xmppPersonaBase.getUserId());
        personaPacket.setPersonaId(personaEntity.getPersonaId());

        openFireSoapBoxCli.send(personaPacket, targetPersonaId);
    }
}
