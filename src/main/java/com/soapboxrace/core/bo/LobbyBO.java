/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.bo;

import com.soapboxrace.core.dao.CarClassListDAO;
import com.soapboxrace.core.dao.EventDAO;
import com.soapboxrace.core.dao.EventSessionDAO;
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

import javax.inject.Inject;
import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@ApplicationScoped

@Transactional
public class LobbyBO {
    private static final Logger logger = LoggerFactory.getLogger(LobbyBO.class);
    @Inject
    private MatchmakingBO matchmakingBO;

    @Inject
    private PersonaBO personaBO;

    @Inject
    private EventDAO eventDao;

    @Inject
    private PersonaDAO personaDao;

    @Inject
    private LobbyDAO lobbyDao;

    @Inject
    private LobbyEntrantDAO lobbyEntrantDao;

    @Inject
    private OpenFireRestApiCli openFireRestApiCli;

    @Inject
    private OpenFireSoapBoxCli openFireSoapBoxCli;

    @Inject
    private LobbyCountdownBO lobbyCountdownBO;

    @Inject
    private LobbyMessagingBO lobbyMessagingBO;

    @Inject
    private ParameterBO parameterBO;

    @Inject
    private EventBO eventBO;

    @Inject
    private CarClassListDAO carClassListDAO;

    @Inject
    private PresenceBO presenceBO;

    @Inject
    private EventSessionDAO eventSessionDAO;

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
        
        // DIAGNOSTIC: Afficher les détails de TOUS les lobbies trouvés par SQL
        if (!allLobbys.isEmpty()) {
            logger.info("JOINFAST DIAGNOSTIC: SQL returned {} lobbies:", allLobbys.size());
            for (LobbyEntity lobby : allLobbys) {
                logger.info("  - LobbyId={}, EventId={}, EventName={}, EventClass={}, LockedClass={}, IsPrivate={}, Entrants={}/{}", 
                    lobby.getId(), 
                    lobby.getEvent().getId(), 
                    lobby.getEvent().getName(),
                    lobby.getEvent().getCarClassHash(),
                    lobby.getLockedCarClassHash(),
                    lobby.getIsPrivate(),
                    lobby.getEntrants().size(),
                    lobby.getEvent().getMaxPlayers());
            }
        }

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
            logger.error("JOINFAST: CRITICAL - SQL level filtering failed! {} lobbies filtered out by level check", 
                        allLobbys.size() - levelFilteredLobbys.size());
            // Afficher les lobbies filtrés
            for (LobbyEntity lobby : allLobbys) {
                EventEntity event = lobby.getEvent();
                if (personaEntity.getLevel() < event.getMinLevel() || personaEntity.getLevel() > event.getMaxLevel()) {
                    logger.error("  - FILTERED BY LEVEL: LobbyId={}, EventId={}, PlayerLevel={}, EventMinLevel={}, EventMaxLevel={}", 
                        lobby.getId(), event.getId(), personaEntity.getLevel(), event.getMinLevel(), event.getMaxLevel());
                }
            }
        }

        // SÉCURITÉ : Double-vérification que le lobby n'est pas verrouillé à une autre classe de voiture
        // NOTE : On ne filtre PLUS par event.carClassHash pour permettre un matchmaking ouvert
        //        Seul le lockedCarClassHash compte (et il est NULL pour les lobbies publics depuis notre fix)
        List<LobbyEntity> carClassFilteredLobbys = new ArrayList<>();
        for (LobbyEntity lobby : levelFilteredLobbys) {
            EventEntity event = lobby.getEvent();
            Integer lobbyLockedClass = lobby.getLockedCarClassHash();
            
            // Pour les lobbies publics (retournés par findAllOpenByLevel), lockedCarClassHash devrait être NULL
            // On vérifie quand même par sécurité
            boolean lobbyLockOK = (lobbyLockedClass == null) || (lobbyLockedClass == carClassHash);
            
            if (lobbyLockOK) {
                carClassFilteredLobbys.add(lobby);
            } else {
                logger.warn("JOINFAST: SECURITY - Lobby locked to different car class: PersonaId={} (CarClass={}) got LobbyId={} (LobbyLock={})", 
                           personaId, carClassHash, lobby.getId(), lobbyLockedClass);
            }
        }
        
        if (carClassFilteredLobbys.size() != levelFilteredLobbys.size()) {
            logger.error("JOINFAST: {} lobbies filtered out by lobby lock check (should be 0 for public lobbies)", 
                        levelFilteredLobbys.size() - carClassFilteredLobbys.size());
            // Afficher les lobbies filtrés
            for (LobbyEntity lobby : levelFilteredLobbys) {
                Integer lobbyLockedClass = lobby.getLockedCarClassHash();
                
                boolean lobbyLockOK = (lobbyLockedClass == null) || (lobbyLockedClass == carClassHash);
                
                if (!lobbyLockOK) {
                    logger.error("  - FILTERED BY LOBBY LOCK: LobbyId={}, LockedClass={}, PlayerClass={}", 
                        lobby.getId(), lobbyLockedClass, carClassHash);
                }
            }
        }

        // Filtrer les lobbies pour exclure les événements ignorés
        List<LobbyEntity> availableLobbys = new ArrayList<>();
        for (LobbyEntity lobby : carClassFilteredLobbys) {
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
        
        logger.info("JOINFAST: PersonaId={} - After filtering ignored events: {} available lobbies (was {} after car class check, {} after level check, {} from SQL)", 
                    personaId, availableLobbys.size(), carClassFilteredLobbys.size(), levelFilteredLobbys.size(), allLobbys.size());
        
        // DIAGNOSTIC: Afficher les lobbies filtrés par événements ignorés ou restrictions
        if (availableLobbys.size() != carClassFilteredLobbys.size()) {
            logger.warn("JOINFAST: {} lobbies filtered out by ignored events or car restrictions", 
                carClassFilteredLobbys.size() - availableLobbys.size());
            for (LobbyEntity filteredLobby : carClassFilteredLobbys) {
                EventEntity filteredEvent = filteredLobby.getEvent();
                
                if (matchmakingBO.isEventIgnored(personaId, filteredEvent.getId())) {
                    logger.warn("  - FILTERED BY IGNORED EVENT: LobbyId={}, EventId={}, EventName={}", 
                        filteredLobby.getId(), filteredEvent.getId(), filteredEvent.getName());
                    continue;
                }
                
                if (filteredEvent.getCarRestriction() != null && !filteredEvent.getCarRestriction().trim().isEmpty()) {
                    if (!eventBO.hasAllowedCarForEvent(personaId, filteredEvent)) {
                        logger.warn("  - FILTERED BY CAR RESTRICTION: LobbyId={}, EventId={}, Restriction={}", 
                            filteredLobby.getId(), filteredEvent.getId(), filteredEvent.getCarRestriction());
                    }
                }
            }
        }

        if (availableLobbys.isEmpty()) {
            // Aucun lobby disponible (soit il n'y en a pas, soit tous sont ignorés) : ajouter le joueur à la file d'attente RaceNow persistante
            logger.info("JOINFAST: No available lobby for PersonaId={}, adding to RaceNow queue", personaId);
            matchmakingBO.addPlayerToRaceNowQueue(personaId, carClassHash, personaEntity.getLevel());
            // Note: addPlayerToRaceNowQueue() déclenche automatiquement un scan immédiat
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
            LobbyEntity lobbyEntity = createLobby(creatorPersonaId, eventId, eventEntity.getCarClassHash(), true);

            if (lobbyEntity != null) {
                int invitationsSent = 0;
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
                        
                        // FIX CRITIQUE: Vérifier les préférences utilisateur avant d'envoyer l'invitation
                        UserEntity recipientUser = recipientPersonaEntity.getUser();
                        
                        // Vérifier declinePrivateInvite (0 = accepter, 1+ = refuser)
                        if (recipientUser.getDeclinePrivateInvite() > 0) {
                            logger.info("Invitation skipped: PersonaId={} has declinePrivateInvite enabled (value={})", 
                                recipientPersonaId, recipientUser.getDeclinePrivateInvite());
                            continue;
                        }
                        
                        // FIX: Vérifier que le joueur n'a pas "Apparaître hors ligne" activé
                        if (recipientUser.isAppearOffline()) {
                            logger.info("Invitation skipped: PersonaId={} has appearOffline enabled", recipientPersonaId);
                            continue;
                        }
                        
                        // OPTIMISATION: Ne plus vérifier Redis - XMPP gère la livraison selon la connexion réelle
                        // Si le joueur est offline, XMPP ignorera le message automatiquement
                        
                        if(eventEntity.getCarClassHash() == 607077938 || carEntity.getCarClassHash() == eventEntity.getCarClassHash()) {
                            lobbyMessagingBO.sendLobbyInvitation(lobbyEntity, recipientPersonaEntity, eventEntity.getLobbyCountdownTime());
                            invitationsSent++;
                            logger.info("Sent private lobby invitation to PersonaId={} for EventId={}", 
                                recipientPersonaId, eventEntity.getId());
                        } else {
                            logger.warn(String.format("Car class mismatch for PersonaId=%d: Event requires %d, player has %d", 
                                recipientPersonaId, eventEntity.getCarClassHash(), carEntity.getCarClassHash()));
                        }
                    }
                }
                
                logger.info("Sent {} private lobby invitations for CreatorPersonaId={}, EventId={}", 
                           invitationsSent, creatorPersonaId, eventEntity.getId());
                
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
        
        // FIX CRITIQUE: Ne PAS verrouiller les lobbies publics par classe de voiture
        // Raison : L'événement a déjà une classe (eventEntity.carClassHash) qui filtre naturellement
        // Le verrouillage empêche les joueurs avec une voiture par défaut différente de rejoindre via RaceNow
        // même s'ils ont une voiture de la bonne classe dans leur garage.
        // 
        // Le verrouillage est utile UNIQUEMENT pour les lobbies privés ou spéciaux (Race Again)
        // où on veut garantir que tous les joueurs utilisent exactement la même classe.
        
        if (isPrivate) {
            // Pour les lobbies privés, on peut verrouiller si le paramètre est activé
            boolean shouldLockCarClass = false;
            try {
                shouldLockCarClass = parameterBO.getBoolParam("SBRWR_LOCK_LOBBY_CAR_CLASS");
            } catch (Exception e) {
                logger.debug("Could not read SBRWR_LOCK_LOBBY_CAR_CLASS parameter, assuming false");
            }
            
            boolean isOpenClassEvent = (eventEntity.getCarClassHash() == 607077938);
            if (shouldLockCarClass && eventEntity.getEventModeId() != 22 && !isOpenClassEvent) {
                lobbyEntity.setLockedCarClassHash(eventEntity.getCarClassHash());
                logger.info("CREATELOBBY: Private lobby locked to car class {} for PersonaId={} on Event {}", 
                    eventEntity.getCarClassHash(), personaId, eventId);
            } else {
                lobbyEntity.setLockedCarClassHash(null);
            }
        } else {
            // Pour les lobbies publics, JAMAIS de verrouillage
            // L'événement gère déjà la classe via eventEntity.carClassHash
            lobbyEntity.setLockedCarClassHash(null);
            logger.debug("CREATELOBBY: Public lobby created without car class lock (EventClass={}) for PersonaId={}", 
                eventEntity.getCarClassHash(), personaId);
        }

        lobbyDao.insert(lobbyEntity);

        PersonaEntity personaEntity = personaDao.find(personaId);
        if (personaEntity == null) {
            logger.error("CREATELOBBY: PersonaEntity not found for PersonaId={}", personaId);
            throw new EngineException(EngineExceptionCode.PersonaNotFound, true);
        }

        // Éviter qu'un créateur reste listé dans un ancien lobby actif.
        ensurePersonaSingleActiveLobby(personaId, lobbyEntity.getId());
        
        CarEntity carEntity = personaBO.getDefaultCarEntity(personaId);
        if (carEntity == null) {
            logger.error("CREATELOBBY: CarEntity not found for PersonaId={}", personaId);
            throw new EngineException(EngineExceptionCode.CarDataInvalid, true);
        }
        
        // NE PAS ajouter automatiquement le créateur aux entrants
        // Il recevra une invitation et devra l'accepter explicitement via acceptinvite()
        // Cela évite les lobbies fantômes avec "1 player" qui ne sont jamais acceptés
        
        logger.info("CREATELOBBY: Created lobby {} for PersonaId={}, sending invitation", 
            lobbyEntity.getId(), personaId);
        
        // Envoyer l'invitation au créateur
        // Il devra appeler acceptinvite() pour rejoindre effectivement le lobby
        if (eventEntity.getCarClassHash() == 607077938 || carEntity.getCarClassHash() == eventEntity.getCarClassHash()) {
            lobbyMessagingBO.sendLobbyInvitation(lobbyEntity, personaEntity, 10000);
        }
        
        // Les invitations aux joueurs en file d'attente et RaceNow sont différées
        // jusqu'à ce que le créateur accepte l'invitation (acceptInvite).
        // Cela évite que le lobby soit trouvable/rejoint avant que le créateur y soit.

        // NE PAS lancer le timer ICI car le lobby a 0 entrants.
        // Le timer sera lancé dans acceptInvite() quand le premier joueur rejoint
        // (ou quand le 2ème rejoint en mode LOBBY_WAIT_FOR_MIN_PLAYERS).
        // Lancer le timer avec 0 entrants causait le lancement de la course en solo
        // avant que quiconque d'autre puisse rejoindre (notamment via Race Again).

        return lobbyEntity;
    }

    /**
     * Remplit les places restantes d'un lobby depuis la file d'attente traditionnelle et la file RaceNow.
     * Appelé après que le créateur du lobby ait accepté son invitation.
     */
    private void fillLobbyFromQueues(LobbyEntity lobbyEntity, int carClassHash) {
        EventEntity eventEntity = lobbyEntity.getEvent();
        int maxPlayers = eventEntity.getMaxPlayers();

        for (int i = 1; i <= maxPlayers - 1; i++) {
            if (lobbyEntity.getEntrants().size() >= maxPlayers) break;

            Long queuePersonaId = matchmakingBO.getPlayerFromQueue(carClassHash);

            if (!queuePersonaId.equals(-1L) && !matchmakingBO.isEventIgnored(queuePersonaId, eventEntity.getId())) {
                if (lobbyEntity.getEntrants().size() < maxPlayers) {
                    PersonaEntity queuePersona = personaDao.find(queuePersonaId);
                    if (queuePersona != null) {
                        lobbyMessagingBO.sendLobbyInvitation(lobbyEntity, queuePersona, eventEntity.getLobbyCountdownTime());
                    }
                }
            }
        }

        checkRaceNowQueueForNewLobby(lobbyEntity, carClassHash);
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

                    // Ne pas inviter un joueur déjà présent dans un autre lobby actif.
                    if (isPersonaInAnotherActiveLobby(personaId, lobbyEntity.getId())) {
                        continue;
                    }

                    Map<String, String> queueData = matchmakingBO.getRaceNowQueueData(personaId);
                    
                    if (queueData == null || queueData.isEmpty()) {
                        continue;
                    }
                    
                    Integer playerCarClass = Integer.parseInt(queueData.get("carClass"));
                    Integer playerLevel = Integer.parseInt(queueData.get("level"));
                    
                    // CRITICAL FIX: Vérifier event.carClassHash ET lobby.lockedCarClassHash
                    Integer eventCarClass = event.getCarClassHash();
                    Integer lobbyLockedClass = lobbyEntity.getLockedCarClassHash();
                    
                    // Vérifier la compatibilité de la classe
                    boolean eventClassOK = (eventCarClass == 607077938) || (eventCarClass.equals(playerCarClass));
                    boolean lobbyLockOK = (lobbyLockedClass == null) || (lobbyLockedClass == 607077938) || (lobbyLockedClass.equals(playerCarClass));
                    boolean carClassCompatible = eventClassOK && lobbyLockOK;
                    
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
        // Nettoyer d'abord toute appartenance a d'autres lobbies actifs pour éviter les doubles affectations.
        ensurePersonaSingleActiveLobby(personaEntity.getPersonaId(), null);

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
                break;
            }
        }

        if (lobbyEntity != null) {
            // Vérifier que le lobby est toujours actif et a de la place avant d'envoyer l'invitation.
            // On ne fait PAS d'insertion en base ici : c'est acceptinvite() qui ajoutera le joueur
            // comme entrant quand il acceptera l'invitation. Cela évite que le joueur reste "fantôme"
            // dans le lobby s'il refuse l'invitation.
            LobbyEntity freshLobby = lobbyDao.find(lobbyEntity.getId());
            if (freshLobby == null || !freshLobby.getIsActive()) {
                return;
            }
            
            List<LobbyEntrantEntity> lobbyEntrants = freshLobby.getEntrants();
            int maxEntrants = freshLobby.getEvent().getMaxPlayers();
            
            if (lobbyEntrants.size() >= maxEntrants) {
                logger.info("JOIN_LOBBY FULL: Persona {} not invited to lobby {} (entrants={}/{})",
                    personaEntity.getPersonaId(), freshLobby.getId(), lobbyEntrants.size(), maxEntrants);
                return;
            }

            lobbyMessagingBO.sendLobbyInvitation(freshLobby, personaEntity, 10000);
        }
    }

    /**
     * Garantit qu'un persona n'appartient qu'a un seul lobby actif.
     */
    private void ensurePersonaSingleActiveLobby(Long personaId, Long targetLobbyId) {
        List<Long> activeLobbyIds = lobbyEntrantDao.findActiveLobbyIdsByPersona(personaId);
        for (Long lobbyId : activeLobbyIds) {
            if (targetLobbyId != null && targetLobbyId.equals(lobbyId)) {
                continue;
            }

            try {
                logger.info("LOBBY_MEMBERSHIP_FIX: Removing PersonaId={} from previous active LobbyId={} before joining LobbyId={}",
                    personaId, lobbyId, targetLobbyId);
                removeEntrantFromLobby(personaId, lobbyId);
            } catch (Exception e) {
                logger.warn("LOBBY_MEMBERSHIP_FIX: Failed to remove PersonaId={} from LobbyId={} (continuing): {}",
                    personaId, lobbyId, e.getMessage());
            }
        }
    }

    /**
     * Retire un persona de tous ses lobbies actifs.
     */
    public void removePersonaFromAllActiveLobbies(Long personaId) {
        ensurePersonaSingleActiveLobby(personaId, null);
    }

    /**
     * Vérifie si un persona appartient déjà à un autre lobby actif.
     */
    private boolean isPersonaInAnotherActiveLobby(Long personaId, Long targetLobbyId) {
        List<Long> activeLobbyIds = lobbyEntrantDao.findActiveLobbyIdsByPersona(personaId);
        for (Long lobbyId : activeLobbyIds) {
            if (targetLobbyId != null && targetLobbyId.equals(lobbyId)) {
                continue;
            }
            return true;
        }
        return false;
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
        
        // CRITIQUE : Retirer le joueur des entrants s'il était déjà ajouté
        // Sinon le lobby reste avec "1 player" dans la DB même si le joueur a décliné
        LobbyEntrantEntity entrantToRemove = null;
        for (LobbyEntrantEntity entrant : lobbyEntity.getEntrants()) {
            if (entrant.getPersona().getPersonaId().equals(activePersonaId)) {
                entrantToRemove = entrant;
                break;
            }
        }
        
        if (entrantToRemove != null) {
            logger.info("DECLINE_INVITE: Removing PersonaId={} from Lobby {} entrants", activePersonaId, lobbyInviteId);
            lobbyEntity.getEntrants().remove(entrantToRemove);
            lobbyEntrantDao.delete(entrantToRemove);
            
            // Si le lobby devient complètement vide après le départ, le supprimer (avec délai pour Race Again)
            long remainingEntrants = lobbyEntrantDao.countByLobby(lobbyInviteId);
            if (remainingEntrants == 0) {
                // Vérifier si c'est un lobby Race Again pour appliquer un délai de grâce
                boolean isRaceAgain = EventResultBO.isRaceAgainLobby(lobbyInviteId);
                
                if (isRaceAgain) {
                    // Lobby Race Again : programmer une suppression différée
                    int graceDelay = parameterBO.getIntParam("RACE_AGAIN_EMPTY_LOBBY_GRACE_PERIOD", 30000);
                    logger.info("RACE_AGAIN: Lobby {} is now empty after decline, scheduling deletion in {} ms", 
                        lobbyInviteId, graceDelay);
                    lobbyCountdownBO.cancelLobbyTimer(lobbyInviteId);
                    lobbyCountdownBO.scheduleRaceAgainLobbyDeletion(lobbyInviteId, graceDelay);
                } else {
                    // Lobby normal : supprimer immédiatement
                    logger.info("DECLINE_INVITE: Lobby {} is now empty, marking as deleted", lobbyInviteId);
                    eventSessionDAO.nullifyLobbyReferences(lobbyInviteId);
                    lobbyDao.markAsDeleted(lobbyEntity);
                    
                    // Annuler le timer si actif
                    lobbyCountdownBO.cancelLobbyTimer(lobbyInviteId);
                }
            }
        }
    }

    public LobbyInfo acceptinvite(Long personaId, Long lobbyInviteId) {
        // RACE CONDITION FIX: Utiliser un verrou pessimiste pour empêcher les jointures concurrentes
        // de dépasser maxPlayers. Sans ce lock, deux threads peuvent lire le même entrant count,
        // passer le check tous les deux, et insérer, dépassant ainsi la limite.
        LobbyEntity lobbyEntity = lobbyDao.findWithLock(lobbyInviteId);
        PersonaEntity personaEntity = personaDao.find(personaId);

        if (lobbyEntity == null) {
            throw new EngineException(EngineExceptionCode.GameDoesNotExist, false);
        }
        
        // CRITICAL FIX: Bloquer les joueurs qui tentent de rejoindre un lobby marqué comme supprimé
        // Cela arrive quand onTimeout() a déjà lancé la course et marqué le lobby comme inactif
        if (lobbyEntity.getIsActive() != null && !lobbyEntity.getIsActive()) {
            logger.info("ACCEPT_INVITE BLOCKED: Persona {} tried to join deleted/inactive lobby {} (race already launched)", 
                personaId, lobbyInviteId);
            throw new EngineException(EngineExceptionCode.GameDoesNotExist, false);
        }

        if (personaEntity == null) {
            throw new EngineException(EngineExceptionCode.PersonaNotFound, false);
        }

        // Nettoyer les anciennes appartenances de lobby avant acceptation.
        ensurePersonaSingleActiveLobby(personaId, lobbyInviteId);
        
        EventEntity event = lobbyEntity.getEvent();
        // SECURITY CHECK: Vérifier que le joueur a bien accès à cet événement selon son niveau
        if (personaEntity.getLevel() < event.getMinLevel() || personaEntity.getLevel() > event.getMaxLevel()) {
            logger.warn(String.format("Invitation level restriction bypass attempt blocked: PersonaId=%d (Level=%d) tried to accept invitation for EventId=%d (MinLevel=%d, MaxLevel=%d)", 
                personaEntity.getPersonaId(), personaEntity.getLevel(), event.getId(), event.getMinLevel(), event.getMaxLevel()));
            throw new EngineException(EngineExceptionCode.GameLocked, false);
        }
        
        // SECURITY CHECK: Vérifier la classe de voiture du joueur par rapport au verrouillage de lobby
        CarEntity playerCar = personaBO.getDefaultCarEntity(personaId);
        int playerCarClass = (playerCar != null) ? playerCar.getCarClassHash() : 0;

        if (lobbyEntity.getLockedCarClassHash() != null && !isCarClassCompatible(playerCarClass, lobbyEntity.getLockedCarClassHash())) {
            logger.warn("Lobby car class lock bypass blocked: PersonaId={} (CarClass={}) tried to accept invitation for lobby locked to CarClass={}",
                personaId, playerCarClass, lobbyEntity.getLockedCarClassHash());
            throw new EngineException(EngineExceptionCode.GameLocked, false);
        }

        int eventId = lobbyEntity.getEvent().getId();

        LobbyCountdown lobbyCountdown = new LobbyCountdown();
        lobbyCountdown.setLobbyId(lobbyInviteId);
        lobbyCountdown.setEventId(eventId);
        lobbyCountdown.setLobbyStuckDurationInMilliseconds(10000);
        
        // Utiliser la méthode centralisée pour obtenir le countdown (param BDD > event)
        int fullCountdownTime = lobbyCountdownBO.getCountdownTimeForLobby(lobbyEntity);
        int initialRemainingTime = lobbyEntity.getLobbyCountdownInMilliseconds(fullCountdownTime);
        
        lobbyCountdown.setLobbyCountdownInMilliseconds(initialRemainingTime);
        lobbyCountdown.setIsWaiting(false);

        ArrayOfLobbyEntrantInfo arrayOfLobbyEntrantInfo = new ArrayOfLobbyEntrantInfo();
        List<LobbyEntrantInfo> lobbyEntrantInfo = arrayOfLobbyEntrantInfo.getLobbyEntrantInfo();

        List<LobbyEntrantEntity> entrants = lobbyEntity.getEntrants();
        int currentSize = entrants.size();
        int maxPlayers = lobbyEntity.getEvent().getMaxPlayers();

        if (currentSize >= maxPlayers) {
            logger.info("ACCEPT_INVITE FULL: Persona {} rejected from lobby {} (entrants={}/{}, entrantIds={})",
                personaId, lobbyInviteId, currentSize, maxPlayers,
                entrants.stream().map(e -> String.valueOf(e.getPersona().getPersonaId())).collect(java.util.stream.Collectors.joining(",")));
            throw new EngineException(EngineExceptionCode.GameLocked, false);
        }

        matchmakingBO.removePlayerFromQueue(personaId);
        // Retirer aussi de la file RaceNow persistante
        matchmakingBO.removePlayerFromRaceNowQueue(personaId);
        
        // ÉTAPE 1 : Vérifier et insérer le joueur AVANT d'envoyer les notifications XMPP
        // Cela garantit que l'état du lobby est cohérent quand les messages arrivent aux clients
        boolean personaInside = isPersonaInside(personaId, entrants);
        int newGridIndex = entrants.size();
        
        // Construire la liste des entrants existants pour la réponse HTTP
        for (LobbyEntrantEntity lobbyEntrantEntity : entrants) {
            LobbyEntrantInfo entrantInfo = new LobbyEntrantInfo();
            entrantInfo.setPersonaId(lobbyEntrantEntity.getPersona().getPersonaId());
            entrantInfo.setLevel(lobbyEntrantEntity.getPersona().getLevel());
            entrantInfo.setGridIndex(lobbyEntrantEntity.getGridIndex());
            lobbyEntrantInfo.add(entrantInfo);
        }
        
        if (!personaInside) {
            LobbyEntrantEntity lobbyEntrantEntity = new LobbyEntrantEntity();
            lobbyEntrantEntity.setPersona(personaEntity);
            lobbyEntrantEntity.setLobby(lobbyEntity);
            lobbyEntrantEntity.setGridIndex(newGridIndex);
            
            lobbyEntrantDao.insert(lobbyEntrantEntity);
            lobbyEntity.getEntrants().add(lobbyEntrantEntity);
            
            // RACE_AGAIN : Annuler tout timer de suppression différée car le lobby n'est plus vide
            lobbyCountdownBO.cancelEmptyLobbyDeletion(lobbyInviteId);
            
            // Premier entrant (créateur) : inviter les joueurs en file d'attente et RaceNow
            if (lobbyEntity.getEntrants().size() == 1 
                    && (lobbyEntity.getIsPrivate() == null || !lobbyEntity.getIsPrivate())) {
                fillLobbyFromQueues(lobbyEntity, playerCarClass);
            }
            
            LobbyEntrantInfo newEntrantInfo = new LobbyEntrantInfo();
            newEntrantInfo.setPersonaId(lobbyEntrantEntity.getPersona().getPersonaId());
            newEntrantInfo.setLevel(lobbyEntrantEntity.getPersona().getLevel());
            newEntrantInfo.setGridIndex(lobbyEntrantEntity.getGridIndex());
            lobbyEntrantInfo.add(newEntrantInfo);
            
            // ÉTAPE 2 : Envoyer les notifications de join APRÈS l'insertion DB
            // Cela garantit que le gridIndex est correct et que l'état est cohérent
            // quand les clients reçoivent le message XMPP
            for (LobbyEntrantEntity existingEntrant : entrants) {
                if (!Objects.equals(personaEntity.getPersonaId(), existingEntrant.getPersona().getPersonaId())) {
                    lobbyMessagingBO.sendJoinMessage(lobbyEntity, personaEntity, existingEntrant.getPersona());
                }
            }
        }
        
        // Marquer que le lobby a eu des joueurs
        lobbyCountdownBO.markLobbyHadPlayers(lobbyEntity.getId());
        lobbyEntity.setHasHadPlayers(true);

        // DEADLOCK FIX : Ne PAS refetch le lobby ici car cela crée un lock pessimiste
        // qui peut entrer en conflit avec onTimeout() ou la distribution des rewards
        // La collection d'entrants est déjà synchronisée dans createLobby()
        
        // Let's pray this will work, i will literally thank personally Leo for this piece of code.
        boolean waitForMinPlayers = parameterBO.getBoolParam("LOBBY_WAIT_FOR_MIN_PLAYERS");
        int currentPlayerCount = lobbyEntity.getEntrants().size();
        // fullCountdownTime déjà défini ci-dessus via getCountdownTimeForLobby()
        
        if(currentPlayerCount == lobbyEntity.getEvent().getMaxPlayers()) {
            // Lobby plein : démarrage rapide en 6 secondes
            XMPP_ResponseTypeLobbyCountdown response = new XMPP_ResponseTypeLobbyCountdown();
            lobbyCountdown.setIsWaiting(false);
            lobbyCountdown.setLobbyCountdownInMilliseconds(6000);
        
            response.setLobbyCountdown(lobbyCountdown);
        
            for (LobbyEntrantEntity lobbyEntrant : lobbyEntity.getEntrants()) {
                openFireSoapBoxCli.send(response, lobbyEntrant.getPersona().getPersonaId());
            }

            lobbyCountdownBO.scheduleLobbyStart(lobbyEntity, 6000);
        } else if (waitForMinPlayers && currentPlayerCount < 2) {
            // Feature activée : maintenir le lobby en attente tant qu'il y a moins de 2 joueurs
            // Le lobby reste trouvable via RaceNow sans timer actif
            logger.info("Lobby {} waiting for more players ({}/2 minimum), keeping in waiting state", 
                lobbyEntity.getId(), currentPlayerCount);
            
            // CRITICAL FIX : Rafraîchir startedTime pour que le lobby soit immédiatement visible
            // dans la fenêtre de recherche (findAllOpen/findAllOpenByLevel).
            // Sans ce refresh, startedTime reste à la date de création du lobby et peut sortir
            // de la fenêtre de recherche avant que refreshWaitingLobbies() ne le rattrape.
            // Cela causait : les joueurs RaceNow ne trouvaient pas les lobbies en attente.
            lobbyEntity.setStartedTime(LocalDateTime.now());
            lobbyDao.update(lobbyEntity);
            
            lobbyCountdown.setIsWaiting(true);
            // IMPORTANT : Envoyer le countdown COMPLET (pas le temps restant basé sur startedTime)
            lobbyCountdown.setLobbyCountdownInMilliseconds(fullCountdownTime);
            // Pas de scheduleLobbyStart() : le timer démarrera quand un 2ème joueur rejoindra
        } else if (waitForMinPlayers && currentPlayerCount >= 2) {
            // Feature activée : on a atteint 2+ joueurs
            boolean timerAlreadyActive = lobbyCountdownBO.hasActiveTimer(lobbyEntity.getId());
            
            if (!timerAlreadyActive) {
                // Premier passage à 2+ joueurs : démarrer le countdown
                logger.info("Lobby {} reached minimum players ({}/2), starting countdown", 
                    lobbyEntity.getId(), currentPlayerCount);
                
                // Rafraîchir le startedTime pour que le countdown démarre MAINTENANT
                lobbyEntity.setStartedTime(LocalDateTime.now());
                lobbyDao.update(lobbyEntity);
                
                // Envoyer le countdown COMPLET (60s) à tous les joueurs
                lobbyCountdown.setIsWaiting(false);
                lobbyCountdown.setLobbyCountdownInMilliseconds(fullCountdownTime);
                
                XMPP_ResponseTypeLobbyCountdown response = new XMPP_ResponseTypeLobbyCountdown();
                response.setLobbyCountdown(lobbyCountdown);
                for (LobbyEntrantEntity lobbyEntrant : lobbyEntity.getEntrants()) {
                    openFireSoapBoxCli.send(response, lobbyEntrant.getPersona().getPersonaId());
                }
                
                lobbyCountdownBO.scheduleLobbyStart(lobbyEntity);
            } else {
                // Countdown déjà en cours : envoyer le temps RESTANT au nouveau joueur
                logger.info("Lobby {} already has countdown running, sending remaining time to player {}", 
                    lobbyEntity.getId(), personaId);
                
                // Calculer le temps restant basé sur le startedTime existant
                int remainingTime = lobbyEntity.getLobbyCountdownInMilliseconds(fullCountdownTime);
                lobbyCountdown.setIsWaiting(false);
                lobbyCountdown.setLobbyCountdownInMilliseconds(remainingTime);
                
                XMPP_ResponseTypeLobbyCountdown response = new XMPP_ResponseTypeLobbyCountdown();
                response.setLobbyCountdown(lobbyCountdown);
                // Envoyer SEULEMENT au joueur qui vient de rejoindre
                openFireSoapBoxCli.send(response, personaId);
            }
        } else {
            // Feature désactivée : démarrer le countdown au premier entrant
            lobbyCountdown.setIsWaiting(false);
            boolean timerAlreadyActive = lobbyCountdownBO.hasActiveTimer(lobbyEntity.getId());
            
            if (!timerAlreadyActive) {
                // Premier joueur : rafraîchir le startedTime et démarrer le countdown maintenant
                // Cela donne le temps complet de countdown pour que d'autres joueurs rejoignent
                lobbyEntity.setStartedTime(LocalDateTime.now());
                lobbyDao.update(lobbyEntity);
                lobbyCountdown.setLobbyCountdownInMilliseconds(fullCountdownTime);
                lobbyCountdownBO.scheduleLobbyStart(lobbyEntity);
                logger.info("Lobby {} timer started at first entrant join (countdown={}ms)", 
                    lobbyEntity.getId(), fullCountdownTime);
            } else {
                // Timer déjà en cours : envoyer le temps restant basé sur startedTime
                int remainingTime = lobbyEntity.getLobbyCountdownInMilliseconds(fullCountdownTime);
                lobbyCountdown.setLobbyCountdownInMilliseconds(remainingTime);
            }
        }

        LobbyInfo lobbyInfoType = new LobbyInfo();
        lobbyInfoType.setCountdown(lobbyCountdown);
        lobbyInfoType.setEntrants(arrayOfLobbyEntrantInfo);
        lobbyInfoType.setEventId(eventId);
        lobbyInfoType.setLobbyInviteId(lobbyInviteId);
        lobbyInfoType.setLobbyId(lobbyInviteId);

        if(parameterBO.getBoolParam("SBRWR_ENABLE_NOPU")) {
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

        // CRITICAL FIX pour loop infini : Supprimer manuellement l'entrant de la collection Java
        // AVANT le bulk DELETE JPQL pour garder la cohérence mémoire/DB
        List<LobbyEntrantEntity> entrants = lobbyEntity.getEntrants();
        LobbyEntrantEntity toRemove = null;
        for (LobbyEntrantEntity entrant : entrants) {
            if (entrant.getPersona().getPersonaId().equals(personaId)) {
                toRemove = entrant;
                break;
            }
        }
        
        if (toRemove != null) {
            entrants.remove(toRemove);
            logger.info("REMOVE_ENTRANT: Removed persona {} from lobby {} collection (size now: {})", 
                personaId, lobbyId, entrants.size());
        } else {
            logger.warn("REMOVE_ENTRANT: Persona {} not found in lobby {} collection (size: {})", 
                personaId, lobbyId, entrants.size());
        }

        // Supprimer l'entrant de la base de données
        lobbyEntrantDao.deleteByPersonaAndLobby(personaEntity, lobbyEntity);

        // Vérification de cohérence: s'assurer que la ligne est bien supprimée.
        long stillPresent = lobbyEntrantDao.countByPersonaAndLobby(personaId, lobbyId);
        if (stillPresent > 0) {
            logger.warn("REMOVE_ENTRANT: Persona {} still present {} time(s) in lobby {} after delete, retrying delete", personaId, stillPresent, lobbyId);
            lobbyEntrantDao.deleteByPersonaAndLobby(personaEntity, lobbyEntity);
        }
        
        // Envoyer le message de départ aux joueurs restants (collection déjà à jour)
        for (LobbyEntrantEntity entity : entrants) {
            lobbyMessagingBO.sendLeaveMessage(lobbyEntity, personaEntity, entity.getPersona());
        }

        // Supprimer le lobby s'il est vide après le départ du joueur.
        // countByLobby() interroge directement la base pour contourner le cache L1 JPA
        // (un bulk DELETE JPQL n'invalide pas le cache).
        long remaining = lobbyEntrantDao.countByLobby(lobbyId);
        if (remaining == 0) {
            // Vérifier si c'est un lobby Race Again pour appliquer un délai de grâce
            boolean isRaceAgain = EventResultBO.isRaceAgainLobby(lobbyId);
            
            if (isRaceAgain) {
                // Lobby Race Again : programmer une suppression différée pour permettre aux autres joueurs d'arriver
                int graceDelay = parameterBO.getIntParam("RACE_AGAIN_EMPTY_LOBBY_GRACE_PERIOD", 30000);
                logger.info("RACE_AGAIN: Lobby {} is now empty after persona {} left, scheduling deletion in {} ms", 
                    lobbyId, personaId, graceDelay);
                lobbyCountdownBO.cancelLobbyTimer(lobbyId);
                lobbyCountdownBO.scheduleRaceAgainLobbyDeletion(lobbyId, graceDelay);
            } else {
                // Lobby normal : supprimer immédiatement comme avant
                lobbyCountdownBO.cancelLobbyTimer(lobbyId);
                logger.info("Lobby {} is now empty after persona {} left, marking as deleted", lobbyId, personaId);
                eventSessionDAO.nullifyLobbyReferences(lobbyId);
                lobbyDao.markAsDeleted(lobbyEntity);
            }
        } else if (remaining == 1 && parameterBO.getBoolParam("LOBBY_WAIT_FOR_MIN_PLAYERS")) {
            // Si le lobby avait un timer de suppression Race Again, l'annuler car il n'est plus vide
            lobbyCountdownBO.cancelEmptyLobbyDeletion(lobbyId);
            
            // Feature activée : si on redescend à 1 joueur, remettre le lobby en attente
            logger.info("Lobby {} now has only 1 player after persona {} left, resetting to waiting state", 
                lobbyId, personaId);
            
            // La collection d'entrants est déjà à jour grâce au refetch ci-dessus
            lobbyCountdownBO.resetSoloLobby(lobbyEntity);
        } else {
            // Si le lobby avait un timer de suppression Race Again, l'annuler car il n'est plus vide
            lobbyCountdownBO.cancelEmptyLobbyDeletion(lobbyId);
        }
    }

    /**
     * Vérifie si la classe de voiture du joueur est compatible avec la classe verrouillée du lobby.
     * Compatible signifie :
     * - Aucune restriction (lobbyCarClassHash == null)
     * - Classe OpenClass (607077938)
     * - Même classe exacte
     * - Classe adjacente (+1/-1 dans la table CAR_CLASSLIST) SI SBRWR_ALLOW_ADJACENT_CAR_CLASSES est activé
     *
     * @param playerCarClassHash Hash de la classe de voiture du joueur
     * @param lobbyCarClassHash Hash de la classe verrouillée du lobby (peut être null)
     * @return true si le joueur peut rejoindre le lobby
     */
    private boolean isCarClassCompatible(int playerCarClassHash, Integer lobbyCarClassHash) {
        // Pas de restriction de classe
        if (lobbyCarClassHash == null) {
            return true;
        }

        // OpenClass (607077938) accepte toutes les classes
        if (lobbyCarClassHash == 607077938) {
            return true;
        }

        // Classe exacte
        if (playerCarClassHash == lobbyCarClassHash) {
            return true;
        }

        // Vérifier si les classes adjacentes sont autorisées (configurable)
        boolean allowAdjacentClasses = false;
        try {
            allowAdjacentClasses = parameterBO.getBoolParam("SBRWR_ALLOW_ADJACENT_CAR_CLASSES");
        } catch (Exception e) {
            // Valeur par défaut : false (désactivé)
            logger.debug("Could not read SBRWR_ALLOW_ADJACENT_CAR_CLASSES parameter, assuming false");
        }

        // Si les classes adjacentes ne sont pas autorisées, retourner false
        if (!allowAdjacentClasses) {
            return false;
        }

        // Vérifier si les classes sont adjacentes (+1/-1)
        try {
            CarClassListEntity playerClass = carClassListDAO.findByHash(playerCarClassHash);
            CarClassListEntity lobbyClass = carClassListDAO.findByHash(lobbyCarClassHash);

            if (playerClass == null || lobbyClass == null) {
                logger.warn("Car class not found in CAR_CLASSLIST: playerHash={}, lobbyHash={}", 
                    playerCarClassHash, lobbyCarClassHash);
                return false;
            }

            // Calculer la différence d'ID dans la table CAR_CLASSLIST
            int idDifference = Math.abs(playerClass.getId() - lobbyClass.getId());
            boolean isAdjacent = (idDifference == 1);

            if (isAdjacent) {
                logger.debug("Adjacent car class accepted: Player class {} (id={}) joining lobby locked to class {} (id={})", 
                    playerClass.getName(), playerClass.getId(), lobbyClass.getName(), lobbyClass.getId());
            }

            return isAdjacent;

        } catch (Exception e) {
            logger.error("Error checking car class compatibility: playerHash={}, lobbyHash={}", 
                playerCarClassHash, lobbyCarClassHash, e);
            return false;
        }
    }
}
