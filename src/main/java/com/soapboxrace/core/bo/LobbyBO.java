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
import com.soapboxrace.jaxb.xmpp.XMPP_ResponseTypeLobbyCountdown;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ejb.EJB;
import javax.ejb.Stateless;

import java.time.LocalDateTime;
import java.util.ArrayList;
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

    @EJB
    private EventBO eventBO;

    public void joinFastLobby(Long personaId, int carClassHash) {
        logger.info("JOINFAST: PersonaId={} attempting to join fast lobby with carClass={}", personaId, carClassHash);
        
        PersonaEntity personaEntity = personaDao.find(personaId);
        if (personaEntity == null) {
            logger.error("JOINFAST: PersonaEntity not found for PersonaId={}", personaId);
            return;
        }
        
        logger.info("JOINFAST: PersonaId={} found, level={}", personaId, personaEntity.getLevel());
        
        List<LobbyEntity> allLobbys = lobbyDao.findAllOpen(carClassHash, personaEntity.getLevel());
        logger.info("JOINFAST: Found {} open lobbies for PersonaId={} (carClass={}, level={})", 
                    allLobbys.size(), personaId, carClassHash, personaEntity.getLevel());

        // SÉCURITÉ : Double-vérification des niveaux après la requête SQL
        // pour s'assurer qu'aucun lobby avec restriction de niveau inappropriée n'est retourné
        List<LobbyEntity> levelFilteredLobbys = new ArrayList<>();
        for (LobbyEntity lobby : allLobbys) {
            EventEntity event = lobby.getEvent();
            if (personaEntity.getLevel() >= event.getMinLevel() && personaEntity.getLevel() <= event.getMaxLevel()) {
                levelFilteredLobbys.add(lobby);
            } else {
                logger.warn("JOINFAST: SECURITY - SQL query returned inappropriate lobby: PersonaId={} (Level={}) got EventId={} (MinLevel={}, MaxLevel={})", 
                           personaId, personaEntity.getLevel(), event.getId(), event.getMinLevel(), event.getMaxLevel());
            }
        }
        
        if (levelFilteredLobbys.size() != allLobbys.size()) {
            logger.error("JOINFAST: CRITICAL - SQL level filtering failed! Expected {} lobbies, got {} after level verification", 
                        levelFilteredLobbys.size(), allLobbys.size());
        }

        // Filtrer les lobbies pour exclure les événements ignorés
        List<LobbyEntity> availableLobbys = new ArrayList<>();
        for (LobbyEntity lobby : levelFilteredLobbys) {
            EventEntity event = lobby.getEvent();
            
            // Vérifier si l'événement est ignoré par le joueur
            if (matchmakingBO.isEventIgnored(personaId, event.getId())) {
                logger.debug("JOINFAST: PersonaId={} - Skipping ignored event {} ({})", 
                            personaId, event.getId(), event.getName());
                continue;
            }
            
            // Vérifier les restrictions de voiture
            if (event.getCarRestriction() != null && !event.getCarRestriction().trim().isEmpty()) {
                if (!eventBO.hasAllowedCarForEvent(personaId, event)) {
                    logger.debug("JOINFAST: PersonaId={} - Skipping car-restricted event {} ({}) - restriction: {}", 
                                personaId, event.getId(), event.getName(), event.getCarRestriction());
                    continue;
                }
            }
            
            availableLobbys.add(lobby);
        }
        
        logger.info("JOINFAST: PersonaId={} - After filtering ignored events: {} available lobbies (was {} after level check, {} from SQL)", 
                    personaId, availableLobbys.size(), levelFilteredLobbys.size(), allLobbys.size());

        if (availableLobbys.isEmpty()) {
            // Aucun lobby disponible (soit il n'y en a pas, soit tous sont ignorés) : ajouter le joueur à la file d'attente RaceNow persistante
            logger.info("JOINFAST: No available lobby for PersonaId={}, adding to RaceNow queue", personaId);
            matchmakingBO.addPlayerToRaceNowQueue(personaId, carClassHash, personaEntity.getLevel());
        } else {
            logger.info("JOINFAST: Available lobby found, joining PersonaId={} to existing lobby", personaId);
            Collections.shuffle(availableLobbys);
            joinLobby(personaEntity, availableLobbys, false); // Ne pas re-vérifier les événements ignorés, on l'a déjà fait
            // Si le joueur rejoint un lobby avec succès, le retirer de la file RaceNow
            logger.info("JOINFAST: PersonaId={} joined lobby successfully, removing from RaceNow queue", personaId);
            matchmakingBO.removePlayerFromRaceNowQueue(personaId);
        }
        
        logger.info("JOINFAST: PersonaId={} joinFastLobby completed", personaId);
    }

    public void joinQueueEvent(Long personaId, int eventId) {
        PersonaEntity personaEntity = personaDao.find(personaId);
        if (personaEntity == null) {
            logger.error("JOINQUEUE: PersonaEntity not found for PersonaId={}", personaId);
            return;
        }
        
        EventEntity eventEntity = eventDao.find(eventId);
        if (eventEntity == null) {
            logger.error("JOINQUEUE: EventEntity not found for EventId={}", eventId);
            return;
        }

        // SÉCURITÉ : Vérifier le niveau du joueur AVANT de permettre l'accès à l'événement
        if (personaEntity.getLevel() < eventEntity.getMinLevel() || personaEntity.getLevel() > eventEntity.getMaxLevel()) {
            logger.info("Level restriction: PersonaId={} (Level={}) cannot access EventId={} (MinLevel={}, MaxLevel={}) - request ignored", 
                personaEntity.getPersonaId(), personaEntity.getLevel(), eventId, eventEntity.getMinLevel(), eventEntity.getMaxLevel());
            return; // Ignorer silencieusement la demande
        }
        
        // SÉCURITÉ : Vérifier les restrictions de voiture
        if (eventEntity.getCarRestriction() != null && !eventEntity.getCarRestriction().trim().isEmpty()) {
            if (!eventBO.hasAllowedCarForEvent(personaId, eventEntity)) {
                logger.info("Car restriction: PersonaId={} cannot access EventId={} (restriction: {}) - request ignored", 
                    personaId, eventId, eventEntity.getCarRestriction());
                return; // Ignorer silencieusement la demande
            }
        }

        CarEntity carEntity = personaBO.getDefaultCarEntity(personaId);
        if (carEntity == null) {
            logger.error("JOINQUEUE: CarEntity not found for PersonaId={}", personaId);
            throw new EngineException(EngineExceptionCode.CarDataInvalid, true);
        }

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
        List<Long> personaIdList = openFireRestApiCli.getAllPersonaByGroup(creatorPersonaId);
        
        EventEntity eventEntity = eventDao.find(eventId);
        if (eventEntity == null) {
            logger.error("PRIVATELOBBY: EventEntity not found for EventId={}", eventId);
            throw new EngineException(EngineExceptionCode.InvalidEntrantEventSession, true);
        }
        
        PersonaEntity creatorPersonaEntity = personaDao.find(creatorPersonaId);
        if (creatorPersonaEntity == null) {
            logger.error("PRIVATELOBBY: CreatorPersonaEntity not found for PersonaId={}", creatorPersonaId);
            throw new EngineException(EngineExceptionCode.InvalidEntrantEventSession, true);
        }
        
        // SÉCURITÉ : Vérifier que le créateur peut accéder à cet événement selon son niveau
        if (creatorPersonaEntity.getLevel() < eventEntity.getMinLevel() || creatorPersonaEntity.getLevel() > eventEntity.getMaxLevel()) {
            logger.warn("Level restriction violation blocked: CreatorPersonaId={} (Level={}) tried to create private lobby for EventId={} (MinLevel={}, MaxLevel={})", 
                creatorPersonaId, creatorPersonaEntity.getLevel(), eventId, eventEntity.getMinLevel(), eventEntity.getMaxLevel());
            throw new EngineException(EngineExceptionCode.InvalidEntrantEventSession, true);
        }
        
        if (!personaIdList.isEmpty()) {
            createLobby(creatorPersonaId, eventId, eventEntity.getCarClassHash(), true);

            LobbyEntity lobbyEntity = lobbyDao.findByEventAndPersona(eventEntity, creatorPersonaId);
            if (lobbyEntity != null) {
                for (Long recipientPersonaId : personaIdList) {
                    if (!recipientPersonaId.equals(creatorPersonaId)) {
                        PersonaEntity recipientPersonaEntity = personaDao.find(recipientPersonaId);
                        
                        if (recipientPersonaEntity == null) {
                            logger.warn(String.format("Recipient PersonaEntity not found for PersonaId=%d", recipientPersonaId));
                            continue;
                        }

                        CarEntity carEntity = personaBO.getDefaultCarEntity(recipientPersonaEntity.getPersonaId());
                        
                        if (carEntity == null) {
                            logger.warn(String.format("Default car not found for PersonaId=%d", recipientPersonaId));
                            continue;
                        }
                        
                        // SECURITY CHECK: Vérifier que le joueur a bien accès à cet événement selon son niveau
                        if (recipientPersonaEntity.getLevel() < eventEntity.getMinLevel() || recipientPersonaEntity.getLevel() > eventEntity.getMaxLevel()) {
                            logger.warn(String.format("Invitation skipped due to level restriction: PersonaId=%d (Level=%d) cannot access EventId=%d (MinLevel=%d, MaxLevel=%d)", 
                                recipientPersonaId, recipientPersonaEntity.getLevel(), eventEntity.getId(), eventEntity.getMinLevel(), eventEntity.getMaxLevel()));
                            continue;
                        }
                        
                        if(eventEntity.getCarClassHash() == 607077938 || carEntity.getCarClassHash() == eventEntity.getCarClassHash()) {
                            lobbyMessagingBO.sendLobbyInvitation(lobbyEntity, recipientPersonaEntity, eventEntity.getLobbyCountdownTime());
                        } else {
                            logger.warn(String.format("Car class mismatch for PersonaId=%d: Event requires %d, player has %d", 
                                recipientPersonaId, eventEntity.getCarClassHash(), carEntity.getCarClassHash()));
                        }
                    }
                }
                
            } else {
                logger.error(String.format("Failed to find created lobby for CreatorPersonaId=%d, EventId=%d", creatorPersonaId, eventId));
            }
        } else {
            logger.warn(String.format("No group members found for CreatorPersonaId=%d", creatorPersonaId));
        }
    }

    public LobbyEntity createLobby(Long personaId, int eventId, int carClassHash, Boolean isPrivate) {
        EventEntity eventEntity = eventDao.find(eventId);
        if (eventEntity == null) {
            logger.error("CREATELOBBY: EventEntity not found for EventId={}", eventId);
            throw new EngineException(EngineExceptionCode.InvalidEntrantEventSession, true);
        }

        LobbyEntity lobbyEntity = new LobbyEntity();
        lobbyEntity.setEvent(eventEntity);
        lobbyEntity.setIsPrivate(isPrivate);
        lobbyEntity.setPersonaId(personaId);
        lobbyEntity.setStartedTime(LocalDateTime.now());
        
        // Verrouiller la classe de voiture si le paramètre est activé et que ce n'est pas le mode 22
        boolean shouldLockCarClass = false;
        try {
            shouldLockCarClass = parameterBO.getBoolParam("SBRWR_LOCK_LOBBY_CAR_CLASS");
        } catch (Exception e) {
            logger.debug("Could not read SBRWR_LOCK_LOBBY_CAR_CLASS parameter, assuming false");
        }
        
        if (shouldLockCarClass && eventEntity.getEventModeId() != 22) {
            // Récupérer la voiture par défaut du joueur pour obtenir sa vraie classe
            CarEntity playerCar = personaBO.getDefaultCarEntity(personaId);
            if (playerCar != null) {
                // Verrouiller le lobby à la classe de voiture réelle du créateur
                lobbyEntity.setLockedCarClassHash(playerCar.getCarClassHash());
                logger.info("CREATELOBBY: Lobby locked to car class {} for PersonaId={} on Event {} (mode {})", 
                    playerCar.getCarClassHash(), personaId, eventId, eventEntity.getEventModeId());
            } else {
                logger.warn("CREATELOBBY: Could not get player car for PersonaId={}, car class lock disabled", personaId);
                lobbyEntity.setLockedCarClassHash(null);
            }
        } else {
            lobbyEntity.setLockedCarClassHash(null);
            if (eventEntity.getEventModeId() == 22) {
                logger.debug("CREATELOBBY: Car class lock disabled for mode 22 (Drag)");
            }
        }

        lobbyDao.insert(lobbyEntity);

        PersonaEntity personaEntity = personaDao.find(personaId);
        if (personaEntity == null) {
            logger.error("CREATELOBBY: PersonaEntity not found for PersonaId={}", personaId);
            throw new EngineException(EngineExceptionCode.PersonaNotFound, true);
        }
        
        CarEntity carEntity = personaBO.getDefaultCarEntity(personaId);
        if (carEntity == null) {
            logger.error("CREATELOBBY: CarEntity not found for PersonaId={}", personaId);
            throw new EngineException(EngineExceptionCode.CarDataInvalid, true);
        }
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
                        if (queuePersona != null) {
                            lobbyMessagingBO.sendLobbyInvitation(lobbyEntity, queuePersona, eventEntity.getLobbyCountdownTime());
                        } else {
                            logger.warn("CREATELOBBY: QueuePersonaEntity not found for PersonaId={}", queuePersonaId);
                        }
                    }
                }
            }
            
            // NOUVEAU : Vérifier aussi la file RaceNow persistante pour inviter les joueurs qui attendent
            checkRaceNowQueueForNewLobby(lobbyEntity, carClassHash);
        }

        if(eventEntity.isRankedMode() == false) {
            lobbyCountdownBO.scheduleLobbyStart(lobbyEntity);
        }

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
                            }
                        }
                    }
                    
                } catch (NumberFormatException e) {
                    logger.warn("Invalid persona ID in RaceNow queue: {}", personaIdStr);
                } catch (Exception e) {
                    logger.error("Error processing RaceNow player {} for new lobby: {}", 
                        personaIdStr, e.getMessage(), e);
                }
            }
            
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
            
            // SECURITY CHECK: Vérifier les restrictions de voiture
            if (event.getCarRestriction() != null && !event.getCarRestriction().trim().isEmpty()) {
                if (!eventBO.hasAllowedCarForEvent(personaEntity.getPersonaId(), event)) {
                    logger.warn("RaceNow car restriction bypass attempt blocked: PersonaId={} tried to join EventId={} (restriction: {})", 
                        personaEntity.getPersonaId(), event.getId(), event.getCarRestriction());
                    continue;
                }
            }
            
            // SECURITY CHECK: Vérifier le verrouillage de classe de voiture du lobby
            if (lobbyEntityTmp.getLockedCarClassHash() != null) {
                CarEntity playerCar = personaBO.getDefaultCarEntity(personaEntity.getPersonaId());
                if (playerCar != null && playerCar.getCarClassHash() != lobbyEntityTmp.getLockedCarClassHash()) {
                    logger.warn("RaceNow car class lock bypass attempt blocked: PersonaId={} (CarClass={}) tried to join lobby locked to CarClass={}", 
                        personaEntity.getPersonaId(), playerCar.getCarClassHash(), lobbyEntityTmp.getLockedCarClassHash());
                    continue;
                }
            }

            int maxEntrants = event.getMaxPlayers();
            List<LobbyEntrantEntity> lobbyEntrants = lobbyEntityTmp.getEntrants();
            int entrantsSize = lobbyEntrants.size();

            if (entrantsSize < maxEntrants) {
                lobbyEntity = lobbyEntityTmp;
                
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
        
        // SECURITY CHECK: Vérifier le verrouillage de classe de voiture du lobby
        if (lobbyEntity.getLockedCarClassHash() != null) {
            CarEntity playerCar = personaBO.getDefaultCarEntity(personaId);
            if (playerCar != null && playerCar.getCarClassHash() != lobbyEntity.getLockedCarClassHash()) {
                logger.warn("Invitation car class lock bypass attempt blocked: PersonaId={} (CarClass={}) tried to accept invitation for lobby locked to CarClass={}", 
                    personaId, playerCar.getCarClassHash(), lobbyEntity.getLockedCarClassHash());
                throw new EngineException(EngineExceptionCode.GameLocked, false);
            }
        }

        int eventId = lobbyEntity.getEvent().getId();

        LobbyCountdown lobbyCountdown = new LobbyCountdown();
        lobbyCountdown.setLobbyId(lobbyInviteId);
        lobbyCountdown.setEventId(eventId);
        lobbyCountdown.setLobbyStuckDurationInMilliseconds(10000);
        lobbyCountdown.setLobbyCountdownInMilliseconds(lobbyEntity.getLobbyCountdownInMilliseconds(lobbyEntity.getEvent().getLobbyCountdownTime()));
        lobbyCountdown.setIsWaiting(lobbyEntity.getEvent().isRankedMode());

        ArrayOfLobbyEntrantInfo arrayOfLobbyEntrantInfo = new ArrayOfLobbyEntrantInfo();
        List<LobbyEntrantInfo> lobbyEntrantInfo = arrayOfLobbyEntrantInfo.getLobbyEntrantInfo();

        List<LobbyEntrantEntity> entrants = lobbyEntity.getEntrants();

        if (entrants.size() >= lobbyEntity.getEvent().getMaxPlayers()) {
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

        // Let's pray this will work, i will literally thank personally Leo for this piece of code.
        if(lobbyEntity.getEntrants().size() == lobbyEntity.getEvent().getMaxPlayers()) {
            XMPP_ResponseTypeLobbyCountdown response = new XMPP_ResponseTypeLobbyCountdown();
            lobbyCountdown.setIsWaiting(false);
            lobbyCountdown.setLobbyCountdownInMilliseconds(6000);
        
            response.setLobbyCountdown(lobbyCountdown);
        
            for (LobbyEntrantEntity lobbyEntrant : lobbyEntity.getEntrants()) {
                openFireSoapBoxCli.send(response, lobbyEntrant.getPersona().getPersonaId());
            }

            lobbyCountdownBO.scheduleLobbyStart(lobbyEntity, 6000);
        } else {
            lobbyCountdown.setIsWaiting(lobbyEntity.getEvent().isRankedMode());
        }

        LobbyInfo lobbyInfoType = new LobbyInfo();
        lobbyInfoType.setCountdown(lobbyCountdown);
        lobbyInfoType.setEntrants(arrayOfLobbyEntrantInfo);
        lobbyInfoType.setEventId(eventId);
        lobbyInfoType.setLobbyInviteId(lobbyInviteId);
        lobbyInfoType.setLobbyId(lobbyInviteId);

        if(parameterBO.getBoolParam("SBRWR_ENABLE_NOPU") && lobbyEntity.getEvent().isRankedMode() == false) {
            int currentRefreshTime = lobbyCountdown.getLobbyCountdownInMilliseconds()-2000;
            if(currentRefreshTime <= 2000) currentRefreshTime = 100;

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
                }, (currentRefreshTime)
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
