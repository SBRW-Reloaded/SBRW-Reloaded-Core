/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.bo;

import com.soapboxrace.core.dao.EventDAO;
import com.soapboxrace.core.dao.LobbyDAO;
import com.soapboxrace.core.dao.LobbyEntrantDAO;
import com.soapboxrace.core.dao.PersonaDAO;
import com.soapboxrace.core.engine.EngineException;
import com.soapboxrace.core.engine.EngineExceptionCode;
import com.soapboxrace.core.jpa.*;
import com.soapboxrace.core.xmpp.OpenFireRestApiCli;
import com.soapboxrace.core.xmpp.OpenFireSoapBoxCli;
import com.soapboxrace.core.xmpp.XmppChat;
import com.soapboxrace.jaxb.http.ArrayOfLobbyEntrantInfo;
import com.soapboxrace.jaxb.http.LobbyCountdown;
import com.soapboxrace.jaxb.http.LobbyEntrantInfo;
import com.soapboxrace.jaxb.http.LobbyInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ejb.EJB;
import javax.ejb.Stateless;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Stateless
public class LobbyBO {
    private static final Logger logger = LoggerFactory.getLogger(LobbyBO.class);
    @EJB
    private MatchmakingBO matchmakingBO;

    @EJB
    private PersonaBO personaBO;

    @EJB
    private EventDAO eventDao;

    @EJB
    private PersonaDAO personaDao;

    @EJB
    private LobbyDAO lobbyDao;

    @EJB
    private LobbyEntrantDAO lobbyEntrantDao;

    @EJB
    private OpenFireRestApiCli openFireRestApiCli;

    @EJB
    private OpenFireSoapBoxCli openFireSoapBoxCli;

    @EJB
    private LobbyCountdownBO lobbyCountdownBO;

    @EJB
    private LobbyMessagingBO lobbyMessagingBO;

    @EJB
    private ParameterBO parameterBO;

    public void joinFastLobby(Long personaId, int carClassHash) {
        PersonaEntity personaEntity = personaDao.find(personaId);
        List<LobbyEntity> lobbys = lobbyDao.findAllOpen(carClassHash, personaEntity.getLevel());

        logger.info(String.format("RaceNow: PersonaId=%d, Level=%d, CarClassHash=%d found %d open lobbies", 
            personaId, personaEntity.getLevel(), carClassHash, lobbys.size()));
        
        // Log des événements trouvés pour diagnostic
        for (LobbyEntity lobby : lobbys) {
            EventEntity event = lobby.getEvent();
            logger.info(String.format("RaceNow found lobby: LobbyId=%d, EventId=%d, EventName=%s, EventCarClass=%d, EventMinLevel=%d, EventMaxLevel=%d, PlayerLevel=%d", 
                lobby.getId(), event.getId(), event.getName(), event.getCarClassHash(), event.getMinLevel(), event.getMaxLevel(), personaEntity.getLevel()));
        }

        if (lobbys.isEmpty()) {
            // Aucun lobby n'est trouvé : ajouter le joueur à la file d'attente RaceNow persistante
            logger.info(String.format("RaceNow: No lobbies found for PersonaId=%d (Level=%d), adding to persistent RaceNow queue", 
                personaId, personaEntity.getLevel()));
            matchmakingBO.addPlayerToRaceNowQueue(personaId, carClassHash, personaEntity.getLevel());
        } else {
            Collections.shuffle(lobbys);
            joinLobby(personaEntity, lobbys, true);
            // Si le joueur rejoint un lobby avec succès, le retirer de la file RaceNow
            matchmakingBO.removePlayerFromRaceNowQueue(personaId);
        }
    }

    public void joinQueueEvent(Long personaId, int eventId) {
        PersonaEntity personaEntity = personaDao.find(personaId);
        EventEntity eventEntity = eventDao.find(eventId);

        CarEntity carEntity = personaBO.getDefaultCarEntity(personaId);

        if (carEntity.getCarClassHash() == 0 || (eventEntity.getCarClassHash() != 607077938 && carEntity.getCarClassHash() != eventEntity.getCarClassHash())) {
            // The client UI does not allow you to join events outside your current car's class
            throw new EngineException(EngineExceptionCode.CarDataInvalid, true);
        }

        List<LobbyEntity> lobbys = lobbyDao.findByEventStarted(eventEntity);
        if (lobbys.size() == 0) {
            createLobby(personaEntity.getPersonaId(), eventId, eventEntity.getCarClassHash(), false);
        } else {
            joinLobby(personaEntity, lobbys);
        }
    }

    public void createPrivateLobby(Long creatorPersonaId, int eventId) {
        logger.info(String.format("Creating private lobby: CreatorPersonaId=%d, EventId=%d", creatorPersonaId, eventId));
        
        List<Long> personaIdList = openFireRestApiCli.getAllPersonaByGroup(creatorPersonaId);
        logger.info(String.format("Found %d personas in group for creator %d: %s", 
            personaIdList.size(), creatorPersonaId, personaIdList.toString()));
        
        EventEntity eventEntity = eventDao.find(eventId);
        if (!personaIdList.isEmpty()) {
            createLobby(creatorPersonaId, eventId, eventEntity.getCarClassHash(), true);

            LobbyEntity lobbyEntity = lobbyDao.findByEventAndPersona(eventEntity, creatorPersonaId);
            if (lobbyEntity != null) {
                logger.info(String.format("Lobby created successfully with ID=%d", lobbyEntity.getId()));
                
                int invitationsSent = 0;
                int invitationsSkipped = 0;
                
                for (Long recipientPersonaId : personaIdList) {
                    if (!recipientPersonaId.equals(creatorPersonaId)) {
                        PersonaEntity recipientPersonaEntity = personaDao.find(recipientPersonaId);
                        
                        if (recipientPersonaEntity == null) {
                            logger.warn(String.format("Recipient PersonaEntity not found for PersonaId=%d", recipientPersonaId));
                            invitationsSkipped++;
                            continue;
                        }

                        CarEntity carEntity = personaBO.getDefaultCarEntity(recipientPersonaEntity.getPersonaId());
                        
                        if (carEntity == null) {
                            logger.warn(String.format("Default car not found for PersonaId=%d", recipientPersonaId));
                            invitationsSkipped++;
                            continue;
                        }
                        
                        logger.info(String.format("Checking car compatibility: EventCarClass=%d, PlayerCarClass=%d, PersonaId=%d", 
                            eventEntity.getCarClassHash(), carEntity.getCarClassHash(), recipientPersonaId));
                        
                        // SECURITY CHECK: Vérifier que le joueur a bien accès à cet événement selon son niveau
                        if (recipientPersonaEntity.getLevel() < eventEntity.getMinLevel() || recipientPersonaEntity.getLevel() > eventEntity.getMaxLevel()) {
                            logger.warn(String.format("Invitation skipped due to level restriction: PersonaId=%d (Level=%d) cannot access EventId=%d (MinLevel=%d, MaxLevel=%d)", 
                                recipientPersonaId, recipientPersonaEntity.getLevel(), eventEntity.getId(), eventEntity.getMinLevel(), eventEntity.getMaxLevel()));
                            invitationsSkipped++;
                            continue;
                        }
                        
                        if(eventEntity.getCarClassHash() == 607077938 || carEntity.getCarClassHash() == eventEntity.getCarClassHash()) {
                            lobbyMessagingBO.sendLobbyInvitation(lobbyEntity, recipientPersonaEntity, eventEntity.getLobbyCountdownTime());
                            invitationsSent++;
                        } else {
                            logger.warn(String.format("Car class mismatch for PersonaId=%d: Event requires %d, player has %d", 
                                recipientPersonaId, eventEntity.getCarClassHash(), carEntity.getCarClassHash()));
                            invitationsSkipped++;
                        }
                    }
                }
                
                logger.info(String.format("Private lobby invitation summary: %d sent, %d skipped", invitationsSent, invitationsSkipped));
            } else {
                logger.error(String.format("Failed to find created lobby for CreatorPersonaId=%d, EventId=%d", creatorPersonaId, eventId));
            }
        } else {
            logger.warn(String.format("No group members found for CreatorPersonaId=%d", creatorPersonaId));
        }
    }

    public LobbyEntity createLobby(Long personaId, int eventId, int carClassHash, Boolean isPrivate) {
        EventEntity eventEntity = eventDao.find(eventId);

        LobbyEntity lobbyEntity = new LobbyEntity();
        lobbyEntity.setEvent(eventEntity);
        lobbyEntity.setIsPrivate(isPrivate);
        lobbyEntity.setPersonaId(personaId);
        lobbyEntity.setStartedTime(LocalDateTime.now());

        lobbyDao.insert(lobbyEntity);

        PersonaEntity personaEntity = personaDao.find(personaId);
        CarEntity carEntity = personaBO.getDefaultCarEntity(personaId);
        if(eventEntity.getCarClassHash() == 607077938 || carEntity.getCarClassHash() == eventEntity.getCarClassHash()) {
            lobbyMessagingBO.sendLobbyInvitation(lobbyEntity, personaEntity, 10000);
        }
        
        if (!isPrivate) {
            // Remplir le lobby avec les joueurs de la file d'attente traditionnelle
            for (int i = 1; i <= lobbyEntity.getEvent().getMaxPlayers() - 1; i++) {
                if (lobbyEntity.getEntrants().size() >= lobbyEntity.getEvent().getMaxPlayers()) break;

                Long queuePersonaId = matchmakingBO.getPlayerFromQueue(carClassHash);

                if (!queuePersonaId.equals(-1L) && !matchmakingBO.isEventIgnored(queuePersonaId, eventId)) {
                    if (lobbyEntity.getEntrants().size() < lobbyEntity.getEvent().getMaxPlayers()) {
                        PersonaEntity queuePersona = personaDao.find(queuePersonaId);
                        lobbyMessagingBO.sendLobbyInvitation(lobbyEntity, queuePersona, eventEntity.getLobbyCountdownTime());
                    }
                }
            }
            
            // NOUVEAU : Vérifier aussi la file RaceNow persistante pour inviter les joueurs qui attendent
            checkRaceNowQueueForNewLobby(lobbyEntity, carClassHash);
        }

        lobbyCountdownBO.scheduleLobbyStart(lobbyEntity);

        return lobbyEntity;
    }

    /**
     * Vérifie la file RaceNow persistante et envoie des invitations pour un nouveau lobby créé
     * 
     * @param lobbyEntity Le lobby nouvellement créé
     * @param carClassHash La classe de voiture du lobby
     */
    private void checkRaceNowQueueForNewLobby(LobbyEntity lobbyEntity, int carClassHash) {
        try {
            EventEntity event = lobbyEntity.getEvent();
            Set<String> raceNowPlayers = matchmakingBO.getRaceNowQueuePlayers();
            
            if (raceNowPlayers.isEmpty()) {
                return;
            }
            
            logger.info("New lobby created (LobbyId={}), checking {} RaceNow players for compatibility", 
                lobbyEntity.getId(), raceNowPlayers.size());
            
            int invitationsSent = 0;
            int maxInvitations = Math.min(raceNowPlayers.size(), 
                event.getMaxPlayers() - lobbyEntity.getEntrants().size());
            
            for (String personaIdStr : raceNowPlayers) {
                if (invitationsSent >= maxInvitations) {
                    break; // Le lobby sera bientôt plein
                }
                
                try {
                    Long personaId = Long.parseLong(personaIdStr);
                    Map<String, String> queueData = matchmakingBO.getRaceNowQueueData(personaId);
                    
                    if (queueData == null || queueData.isEmpty()) {
                        continue;
                    }
                    
                    Integer playerCarClass = Integer.parseInt(queueData.get("carClass"));
                    Integer playerLevel = Integer.parseInt(queueData.get("level"));
                    
                    // Vérifier la compatibilité
                    boolean carClassCompatible = playerCarClass.equals(carClassHash) || 
                                               carClassHash == 607077938 || 
                                               playerCarClass.equals(607077938);
                    
                    boolean levelCompatible = playerLevel >= event.getMinLevel() && 
                                            playerLevel <= event.getMaxLevel();
                    
                    if (carClassCompatible && levelCompatible) {
                        // Vérifier que l'événement n'est pas ignoré
                        if (!matchmakingBO.isEventIgnored(personaId, event.getId())) {
                            PersonaEntity personaEntity = personaDao.find(personaId);
                            
                            if (personaEntity != null) {
                                // Envoyer l'invitation
                                lobbyMessagingBO.sendLobbyInvitation(lobbyEntity, personaEntity, 
                                    event.getLobbyCountdownTime());
                                
                                invitationsSent++;
                                
                                logger.info("RaceNow invitation sent for new lobby: PersonaId={} invited to LobbyId={} (EventId={})", 
                                    personaId, lobbyEntity.getId(), event.getId());
                            }
                        } else {
                            logger.debug("PersonaId={} has ignored EventId={}, skipping invitation", 
                                personaId, event.getId());
                        }
                    } else {
                        logger.debug("PersonaId={} not compatible: CarClass={}/{}, Level={} (event range: {}-{})", 
                            personaId, playerCarClass, carClassHash, playerLevel, 
                            event.getMinLevel(), event.getMaxLevel());
                    }
                    
                } catch (NumberFormatException e) {
                    logger.warn("Invalid persona ID in RaceNow queue: {}", personaIdStr);
                } catch (Exception e) {
                    logger.error("Error processing RaceNow player {} for new lobby: {}", 
                        personaIdStr, e.getMessage(), e);
                }
            }
            
            logger.info("Sent {} RaceNow invitations for new LobbyId={}", invitationsSent, lobbyEntity.getId());
            
        } catch (Exception e) {
            logger.error("Error checking RaceNow queue for new lobby: {}", e.getMessage(), e);
        }
    }

    private void joinLobby(PersonaEntity personaEntity, List<LobbyEntity> lobbys) {
        joinLobby(personaEntity, lobbys, false);
    }

    private void joinLobby(PersonaEntity personaEntity, List<LobbyEntity> lobbys, boolean checkIgnoredEvents) {
        LobbyEntity lobbyEntity = null;
        for (LobbyEntity lobbyEntityTmp : lobbys) {
            if (lobbyEntityTmp.getIsPrivate()) continue;

            EventEntity event = lobbyEntityTmp.getEvent();
            if (checkIgnoredEvents && matchmakingBO.isEventIgnored(personaEntity.getPersonaId(), event.getId()))
                continue;

            // SECURITY CHECK: Vérifier que le joueur a bien accès à cet événement selon son niveau
            if (personaEntity.getLevel() < event.getMinLevel() || personaEntity.getLevel() > event.getMaxLevel()) {
                logger.warn(String.format("RaceNow level restriction bypass attempt blocked: PersonaId=%d (Level=%d) tried to join EventId=%d (MinLevel=%d, MaxLevel=%d)", 
                    personaEntity.getPersonaId(), personaEntity.getLevel(), event.getId(), event.getMinLevel(), event.getMaxLevel()));
                continue;
            }

            int maxEntrants = event.getMaxPlayers();
            List<LobbyEntrantEntity> lobbyEntrants = lobbyEntityTmp.getEntrants();
            int entrantsSize = lobbyEntrants.size();

            if (entrantsSize < maxEntrants) {
                lobbyEntity = lobbyEntityTmp;
                logger.info(String.format("RaceNow: PersonaId=%d (Level=%d) successfully joining EventId=%d (MinLevel=%d, MaxLevel=%d)", 
                    personaEntity.getPersonaId(), personaEntity.getLevel(), event.getId(), event.getMinLevel(), event.getMaxLevel()));
                
                if (!isPersonaInside(personaEntity.getPersonaId(), lobbyEntrants)) {
                    LobbyEntrantEntity lobbyEntrantEntity = new LobbyEntrantEntity();
                    lobbyEntrantEntity.setPersona(personaEntity);
                    lobbyEntrantEntity.setLobby(lobbyEntity);
                    lobbyEntrants.add(lobbyEntrantEntity);
                }
                break;
            }
        }

        if (lobbyEntity != null) {
            lobbyMessagingBO.sendLobbyInvitation(lobbyEntity, personaEntity, 10000);
        }
    }

    private boolean isPersonaInside(Long personaId, List<LobbyEntrantEntity> lobbyEntrants) {
        for (LobbyEntrantEntity lobbyEntrantEntity : lobbyEntrants) {
            Long entrantPersonaId = lobbyEntrantEntity.getPersona().getPersonaId();
            if (Objects.equals(entrantPersonaId, personaId)) {
                return true;
            }
        }
        return false;
    }

    public void declineinvite(Long activePersonaId, Long lobbyInviteId) {
        LobbyEntity lobbyEntity = lobbyDao.find(lobbyInviteId);

        if (lobbyEntity == null) {
            return;
        }

        matchmakingBO.ignoreEvent(activePersonaId, lobbyEntity.getEvent());

    }

    public LobbyInfo acceptinvite(Long personaId, Long lobbyInviteId) {
        LobbyEntity lobbyEntity = lobbyDao.find(lobbyInviteId);
        PersonaEntity personaEntity = personaDao.find(personaId);

        if (lobbyEntity == null) {
            throw new EngineException(EngineExceptionCode.GameDoesNotExist, false);
        }

        if (personaEntity == null) {
            throw new EngineException(EngineExceptionCode.PersonaNotFound, false);
        }

        // SECURITY CHECK: Vérifier que le joueur a bien accès à cet événement selon son niveau
        EventEntity event = lobbyEntity.getEvent();
        if (personaEntity.getLevel() < event.getMinLevel() || personaEntity.getLevel() > event.getMaxLevel()) {
            logger.warn(String.format("Invitation level restriction bypass attempt blocked: PersonaId=%d (Level=%d) tried to accept invitation for EventId=%d (MinLevel=%d, MaxLevel=%d)", 
                personaEntity.getPersonaId(), personaEntity.getLevel(), event.getId(), event.getMinLevel(), event.getMaxLevel()));
            throw new EngineException(EngineExceptionCode.GameLocked, false);
        }

        int eventId = lobbyEntity.getEvent().getId();

        LobbyCountdown lobbyCountdown = new LobbyCountdown();
        lobbyCountdown.setLobbyId(lobbyInviteId);
        lobbyCountdown.setEventId(eventId);
        lobbyCountdown.setLobbyCountdownInMilliseconds(lobbyEntity.getLobbyCountdownInMilliseconds(lobbyEntity.getEvent().getLobbyCountdownTime()));
        lobbyCountdown.setLobbyStuckDurationInMilliseconds(10000);

        ArrayOfLobbyEntrantInfo arrayOfLobbyEntrantInfo = new ArrayOfLobbyEntrantInfo();
        List<LobbyEntrantInfo> lobbyEntrantInfo = arrayOfLobbyEntrantInfo.getLobbyEntrantInfo();

        List<LobbyEntrantEntity> entrants = lobbyEntity.getEntrants();

        if (entrants.size() >= lobbyEntity.getEvent().getMaxPlayers()) {
            throw new EngineException(EngineExceptionCode.GameLocked, false);
        }

        if (lobbyCountdown.getLobbyCountdownInMilliseconds() <= 6000) {
            throw new EngineException(EngineExceptionCode.GameLocked, false);
        }

        matchmakingBO.removePlayerFromQueue(personaId);
        // Retirer aussi de la file RaceNow persistante
        matchmakingBO.removePlayerFromRaceNowQueue(personaId);
        for (LobbyEntrantEntity lobbyEntrantEntity : entrants) {
            if (!Objects.equals(personaEntity.getPersonaId(), lobbyEntrantEntity.getPersona().getPersonaId())) {
                lobbyMessagingBO.sendJoinMessage(lobbyEntity, personaEntity, lobbyEntrantEntity.getPersona());
            }
        }
        boolean personaInside = false;
        for (LobbyEntrantEntity lobbyEntrantEntity : entrants) {
            LobbyEntrantInfo LobbyEntrantInfo = new LobbyEntrantInfo();
            LobbyEntrantInfo.setPersonaId(lobbyEntrantEntity.getPersona().getPersonaId());
            LobbyEntrantInfo.setLevel(lobbyEntrantEntity.getPersona().getLevel());
            LobbyEntrantInfo.setGridIndex(lobbyEntrantEntity.getGridIndex());
            lobbyEntrantInfo.add(LobbyEntrantInfo);
            if (lobbyEntrantEntity.getPersona().getPersonaId().equals(personaId)) {
                personaInside = true;
            }
        }
        if (!personaInside) {
            LobbyEntrantEntity lobbyEntrantEntity = new LobbyEntrantEntity();
            lobbyEntrantEntity.setPersona(personaEntity);
            lobbyEntrantEntity.setLobby(lobbyEntity);
            lobbyEntrantEntity.setGridIndex(entrants.size());
            lobbyEntity.getEntrants().add(lobbyEntrantEntity);
            lobbyDao.update(lobbyEntity);
            LobbyEntrantInfo LobbyEntrantInfo = new LobbyEntrantInfo();
            LobbyEntrantInfo.setPersonaId(lobbyEntrantEntity.getPersona().getPersonaId());
            LobbyEntrantInfo.setLevel(lobbyEntrantEntity.getPersona().getLevel());
            LobbyEntrantInfo.setGridIndex(lobbyEntrantEntity.getGridIndex());
            lobbyEntrantInfo.add(LobbyEntrantInfo);
        }

        LobbyInfo lobbyInfoType = new LobbyInfo();
        lobbyInfoType.setCountdown(lobbyCountdown);
        lobbyInfoType.setEntrants(arrayOfLobbyEntrantInfo);
        lobbyInfoType.setEventId(eventId);
        lobbyInfoType.setLobbyInviteId(lobbyInviteId);
        lobbyInfoType.setLobbyId(lobbyInviteId);

        if(parameterBO.getBoolParam("SBRWR_ENABLE_NOPU")) {
            new java.util.Timer().schedule( 
                new java.util.TimerTask() {
                    @Override
                    public void run() {
                        LobbyEntity lobbyEntity = lobbyDao.find(lobbyInviteId);
                        LobbyEntrantEntity userCheck = lobbyEntrantDao.getVoteStatus(personaEntity, lobbyEntity);

                        if(userCheck != null) {
                            List<LobbyEntrantEntity> lobbyEntrants = lobbyEntity.getEntrants();

                            Integer totalVotes = lobbyEntrantDao.getVotes(lobbyEntity);
                            Integer totalUsersInLobby = lobbyEntrants.size();
                            Integer totalVotesPercentage = Math.round((totalVotes * 100.0f) / totalUsersInLobby);
                            
                            if(totalVotesPercentage < parameterBO.getIntParam("SBRWR_NOPU_REQUIREDPERCENT")) {
                                if(parameterBO.getBoolParam("SBRWR_NOPU_SHOW_NOTENOUGHVOTES")) {
                                    openFireSoapBoxCli.send(XmppChat.createSystemMessage("SBRWR_NOPU_INFO_NOTENOUGHVOTES"), personaEntity.getPersonaId());
                                }
                            } else {
                                openFireSoapBoxCli.send(XmppChat.createSystemMessage("SBRWR_NOPU_INFO_SUCCESS"), personaEntity.getPersonaId());
                            }
                        }
                    }
                }, (lobbyCountdown.getLobbyCountdownInMilliseconds()-5000)
            );
        }

        return lobbyInfoType;
    }

    public void removeEntrantFromLobby(Long personaId, Long lobbyId) {
        LobbyEntity lobbyEntity = lobbyDao.find(lobbyId);
        PersonaEntity personaEntity = personaDao.find(personaId);

        if (lobbyEntity == null) {
            throw new EngineException(EngineExceptionCode.GameDoesNotExist, false);
        }

        if (personaEntity == null) {
            throw new EngineException(EngineExceptionCode.PersonaNotFound, false);
        }

        lobbyEntrantDao.deleteByPersonaAndLobby(personaEntity, lobbyEntity);
        List<LobbyEntrantEntity> listLobbyEntrantEntity = lobbyEntity.getEntrants();
        for (LobbyEntrantEntity entity : listLobbyEntrantEntity) {
            if (!Objects.equals(entity.getPersona().getPersonaId(), personaId)) {
                lobbyMessagingBO.sendLeaveMessage(lobbyEntity, personaEntity, entity.getPersona());
            }
        }
    }
}
