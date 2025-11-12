/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.bo;

import com.soapboxrace.core.dao.EventDAO;
import com.soapboxrace.core.dao.LobbyDAO;
import com.soapboxrace.core.dao.PersonaDAO;
import com.soapboxrace.core.jpa.EventEntity;
import com.soapboxrace.core.jpa.LobbyEntity;
import com.soapboxrace.core.jpa.PersonaEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import javax.ejb.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service de surveillance pour RaceNow persistant.
 * Vérifie périodiquement s'il y a de nouveaux lobbies disponibles 
 * pour les joueurs en file d'attente RaceNow.
 * 
 * @author SBRW Core Team
 */
@Singleton
@Startup
@Lock(LockType.READ)
public class RaceNowMonitorBO {
    private static final Logger logger = LoggerFactory.getLogger(RaceNowMonitorBO.class);
    
    // Compteur pour le heartbeat (log toutes les 10 exécutions)
    private volatile long executionCounter = 0;
    
    @Resource
    private TimerService timerService;
    
    @EJB
    private MatchmakingBO matchmakingBO;
    
    @EJB
    private LobbyDAO lobbyDAO;
    
    @EJB
    private PersonaDAO personaDAO;
    
    @EJB
    private EventDAO eventDAO;
    
    @EJB
    private LobbyBO lobbyBO;
    
    @EJB
    private LobbyMessagingBO lobbyMessagingBO;
    
    @EJB
    private ParameterBO parameterBO;
    
    @EJB
    private EventBO eventBO;
    
    @PostConstruct
    public void initialize() {
        try {
            // RaceNow monitor initializing...
            
            // Démarrer la surveillance toutes les 5 secondes par défaut
            TimerConfig timerConfig = new TimerConfig();
            timerConfig.setInfo("RaceNowMonitor");
            timerConfig.setPersistent(false);
            
            // Délai initial de 3 secondes, puis intervalle de 5 secondes par défaut (plus réactif)
            long initialDelay = 3000; // 3 secondes
            long interval = 5000; // 5 secondes par défaut
            
            // Essayer de lire la configuration, sinon utiliser la valeur par défaut
            try {
                int configInterval = parameterBO.getIntParam("SBRWR_RACENOW_MONITOR_INTERVAL");
                interval = configInterval * 1000;
                // Interval configured from database
            } catch (Exception e) {
                // Could not read SBRWR_RACENOW_MONITOR_INTERVAL parameter, using default 5s
            }
            
            timerService.createIntervalTimer(initialDelay, interval, timerConfig);
            
            // RaceNow monitor initialized successfully
            
        } catch (Exception e) {
            logger.error("CRITICAL: Failed to initialize RaceNow monitor: {}", e.getMessage(), e);
            logger.error("RaceNow persistent system will NOT work!");
        }
    }
    
    @Timeout
    @Lock(LockType.WRITE)
    public void monitorRaceNowQueue(Timer timer) {
        long startTime = System.currentTimeMillis();
        executionCounter++;
        
        try {
            // Log heartbeat toutes les 10 exécutions ou s'il y a des joueurs en file
            boolean shouldLogDetails = (executionCounter % 10 == 0);
            
            if (shouldLogDetails) {
                // Heartbeat tick
            } else {
                // Monitor tick
            }
            
            // Vérifier si le système RaceNow persistant est activé
            boolean isEnabled = true; // Par défaut activé
            try {
                isEnabled = parameterBO.getBoolParam("SBRWR_RACENOW_PERSISTENT_ENABLED");
                if (shouldLogDetails) {
                    // RaceNow persistent system status checked
                }
            } catch (Exception e) {
                // Could not read SBRWR_RACENOW_PERSISTENT_ENABLED parameter, assuming enabled
            }
            
            if (!isEnabled) {
                // RaceNow persistent system is disabled
                return; // Système désactivé
            }
            
            // Récupérer tous les joueurs en attente RaceNow
            Set<String> playersInQueue = matchmakingBO.getRaceNowQueuePlayers();
            
            // Debug: Afficher les détails de la queue récupérée
            if (!playersInQueue.isEmpty() || shouldLogDetails) {
                // Queue data retrieved
            }
            
            // Vérifier s'il y a des joueurs à scanner immédiatement
            Set<String> immediateScanPlayers = matchmakingBO.getAndClearImmediateScanPlayers();
            
            // Combiner les joueurs en attente normale et ceux demandant un scan immédiat
            Set<String> allPlayersToProcess = new HashSet<>(playersInQueue);
            if (!immediateScanPlayers.isEmpty()) {
                // Ajouter les joueurs du scan immédiat (éviter les doublons avec addAll)
                allPlayersToProcess.addAll(immediateScanPlayers);
                // Processing immediate scan requests
            }
            
            // Si il y a des joueurs en file, toujours logger les détails
            if (!allPlayersToProcess.isEmpty()) {
                shouldLogDetails = true;
                // Monitor active
            }
            
            // Queue status logging removed to reduce log noise
            
            if (allPlayersToProcess.isEmpty()) {
                if (shouldLogDetails) {
                    // No players in queue
                }
                return; // Rien à faire
            }
            
            // Processing players from queue
            
            int playersProcessed = 0;
            for (String personaIdStr : allPlayersToProcess) {
                try {
                    Long personaId = Long.parseLong(personaIdStr);
                    // Processing persona
                    checkForAvailableLobbies(personaId);
                } catch (NumberFormatException e) {
                    // Invalid persona ID removed
                    try {
                        matchmakingBO.removePlayerFromRaceNowQueue(Long.parseLong(personaIdStr));
                        // Invalid persona removed from queue
                    } catch (Exception ex) {
                        logger.error("Failed to remove invalid persona ID from queue: {}", personaIdStr);
                    }
                }
            }
            
            long duration = System.currentTimeMillis() - startTime;
            // RaceNow monitor cycle completed
            
        } catch (Exception e) {
            logger.error("CRITICAL ERROR in RaceNow monitor (execution #{}): {}", executionCounter, e.getMessage(), e);
            logger.error("Stack trace:", e);
        } finally {
            long totalDuration = System.currentTimeMillis() - startTime;
            if (executionCounter % 10 == 0 || totalDuration > 1000) { // Log si c'est un heartbeat ou si l'exécution prend du temps
                // Monitor tick finished
            } else {
                // Monitor tick finished
            }
        }
    }
    
    /**
     * Vérifie s'il y a des lobbies disponibles pour un joueur spécifique
     * 
     * @param personaId ID du persona à vérifier
     */
    private void checkForAvailableLobbies(Long personaId) {
        try {
            Map<String, String> queueData = matchmakingBO.getRaceNowQueueData(personaId);
            if (queueData == null || queueData.isEmpty()) {
                // No queue data found, removing from queue
                matchmakingBO.removePlayerFromRaceNowQueue(personaId);
                return;
            }
            
            Integer carClassHash = Integer.parseInt(queueData.get("carClass"));
            Integer playerLevel = Integer.parseInt(queueData.get("level"));
            Long timestamp = Long.parseLong(queueData.get("timestamp"));
            
            // Checking lobbies for persona
            
            // Vérifier que le joueur n'attend pas depuis trop longtemps (éviter les timeouts)
            long maxWaitTime = 30 * 60 * 1000; // 30 minutes par défaut
            try {
                maxWaitTime = parameterBO.getIntParam("SBRWR_RACENOW_MAX_WAIT_MINUTES") * 60 * 1000;
            } catch (Exception e) {
                // Using default 30min wait time
            }
            if (System.currentTimeMillis() - timestamp > maxWaitTime) {
                // RaceNow timeout, removing from queue
                matchmakingBO.removePlayerFromRaceNowQueue(personaId);
                return;
            }
            
            // Rechercher des lobbies disponibles
            List<LobbyEntity> availableLobbies = lobbyDAO.findAllOpen(carClassHash, playerLevel);
            
            // SÉCURITÉ : Double-vérification des niveaux après la requête SQL
            List<LobbyEntity> levelVerifiedLobbies = new ArrayList<>();
            for (LobbyEntity lobby : availableLobbies) {
                EventEntity event = lobby.getEvent();
                if (playerLevel >= event.getMinLevel() && playerLevel <= event.getMaxLevel()) {
                    levelVerifiedLobbies.add(lobby);
                } else {
                    // Security: SQL query returned inappropriate lobby
                }
            }
            
            if (levelVerifiedLobbies.size() != availableLobbies.size()) {
                logger.error("RACENOW_MONITOR: CRITICAL - SQL level filtering failed! Expected {} lobbies, got {} after level verification", 
                            levelVerifiedLobbies.size(), availableLobbies.size());
            }
            
            // Level-verified lobbies found
            
            if (!levelVerifiedLobbies.isEmpty()) {
                PersonaEntity personaEntity = personaDAO.find(personaId);
                if (personaEntity != null) {
                    // Found available lobbies, attempting to join
                    // Faire rejoindre directement le joueur au lieu d'envoyer juste une invitation
                    boolean joinedSuccessfully = attemptToJoinLobbyDirectly(personaEntity, levelVerifiedLobbies);
                    
                    if (joinedSuccessfully) {
                        matchmakingBO.removePlayerFromRaceNowQueue(personaId);
                        // Successfully auto-joined lobby
                    } else {
                        // Could not join any lobby, staying in queue
                    }
                } else {
                    // PersonaEntity not found, removing from queue
                    matchmakingBO.removePlayerFromRaceNowQueue(personaId);
                }
            } else {
                // Aucun lobby disponible : vérifier si le joueur attend depuis assez longtemps
                // pour créer automatiquement un lobby
                long waitTime = System.currentTimeMillis() - timestamp;
                long autoCreateDelay = 30 * 1000; // 30 secondes par défaut
                
                try {
                    autoCreateDelay = parameterBO.getIntParam("SBRWR_RACENOW_AUTO_CREATE_DELAY") * 1000L;
                } catch (Exception e) {
                    logger.debug("Could not read SBRWR_RACENOW_AUTO_CREATE_DELAY parameter, using default 30s");
                }
                
                if (waitTime >= autoCreateDelay) {
                    logger.info("PersonaId={} has been waiting for {}s without finding a lobby, attempting to create one automatically", 
                        personaId, waitTime / 1000);
                    
                    // Créer automatiquement un lobby
                    boolean lobbyCreated = createAutoLobbyForPlayer(personaId, playerLevel, carClassHash);
                    
                    if (lobbyCreated) {
                        logger.info("Successfully created auto-lobby for PersonaId={}", personaId);
                        matchmakingBO.removePlayerFromRaceNowQueue(personaId);
                    } else {
                        logger.warn("Failed to create auto-lobby for PersonaId={}, player stays in queue", personaId);
                    }
                } else {
                    logger.debug("PersonaId={} waiting for {}s (need {}s before auto-lobby creation)", 
                        personaId, waitTime / 1000, autoCreateDelay / 1000);
                }
            }
            
        } catch (Exception e) {
            logger.error("Error checking lobbies for PersonaId={}: {}", personaId, e.getMessage(), e);
        }
    }
    
    /**
     * Tente de faire rejoindre directement un joueur à un lobby disponible
     * 
     * @param personaEntity Le persona du joueur
     * @param availableLobbies Liste des lobbies disponibles
     * @return true si le joueur a rejoint un lobby avec succès
     */
    private boolean attemptToJoinLobbyDirectly(PersonaEntity personaEntity, List<LobbyEntity> availableLobbies) {
        try {
            // Créer une liste d'un seul lobby à la fois pour utiliser la méthode joinLobby de LobbyBO
            for (LobbyEntity lobbyEntity : availableLobbies) {
                if (lobbyEntity.getIsPrivate()) continue;

                EventEntity event = lobbyEntity.getEvent();
                
                // Vérification de sécurité : niveau
                if (personaEntity.getLevel() < event.getMinLevel() || personaEntity.getLevel() > event.getMaxLevel()) {
                    // Level not compatible with event
                    continue;
                }
                
                // Vérification des restrictions de voiture
                if (event.getCarRestriction() != null && !event.getCarRestriction().trim().isEmpty()) {
                    if (!eventBO.hasAllowedCarForEvent(personaEntity.getPersonaId(), event)) {
                        // Does not have required car for event
                        continue;
                    }
                }
                
                // Vérifier si l'événement est ignoré
                if (matchmakingBO.isEventIgnored(personaEntity.getPersonaId(), event.getId())) {
                    // Event ignored by player
                    continue;
                }

                int maxEntrants = event.getMaxPlayers();
                int currentEntrants = lobbyEntity.getEntrants().size();

                if (currentEntrants < maxEntrants) {
                    // Vérifier que le joueur n'est pas déjà dans ce lobby
                    boolean alreadyInLobby = lobbyEntity.getEntrants().stream()
                        .anyMatch(entrant -> entrant.getPersona().getPersonaId().equals(personaEntity.getPersonaId()));
                    
                    if (!alreadyInLobby) {
                        try {
                            // Envoyer une invitation automatique que le client RaceNow devrait accepter
                            lobbyMessagingBO.sendLobbyInvitation(lobbyEntity, personaEntity, 10000);
                            
                            // RaceNow auto-invitation sent
                            
                            return true; // Invitation envoyée avec succès
                            
                        } catch (Exception e) {
                            logger.error("Failed to join PersonaId={} to LobbyId={}: {}", 
                                personaEntity.getPersonaId(), lobbyEntity.getId(), e.getMessage(), e);
                            continue; // Essayer le lobby suivant
                        }
                    } else {
                        // Player already in lobby
                    }
                } else {
                    // Lobby is full
                }
            }
            
            return false; // Aucun lobby disponible trouvé
            
        } catch (Exception e) {
            logger.error("Error attempting to join lobby for PersonaId={}: {}", 
                personaEntity.getPersonaId(), e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Crée automatiquement un lobby pour un joueur en attente RaceNow
     * Sélectionne aléatoirement un événement parmi ceux éligibles (enabled=1, eventModeId 4 ou 9)
     * et compatible avec la classe de voiture du joueur (OpenClass ou classe correspondante)
     * 
     * @param personaId ID du persona
     * @param playerLevel Niveau du joueur
     * @param carClassHash Classe de voiture du joueur
     * @return true si le lobby a été créé avec succès
     */
    private boolean createAutoLobbyForPlayer(Long personaId, int playerLevel, int carClassHash) {
        try {
            // Récupérer les événements éligibles pour la création automatique de lobby
            // Filtre par niveau, eventModeId (4 ou 9), et carClassHash (OpenClass ou classe du joueur)
            List<EventEntity> eligibleEvents = eventDAO.findEligibleForAutoLobby(playerLevel, carClassHash);
            
            if (eligibleEvents.isEmpty()) {
                logger.warn("No eligible events found for auto-lobby creation (PersonaId={}, Level={}, CarClass={})", 
                    personaId, playerLevel, carClassHash);
                return false;
            }
            
            PersonaEntity personaEntity = personaDAO.find(personaId);
            if (personaEntity == null) {
                logger.error("PersonaEntity not found for PersonaId={}", personaId);
                return false;
            }
            
            // Filtrer les événements ignorés et ceux avec restriction de voiture
            List<EventEntity> validEvents = new ArrayList<>();
            for (EventEntity event : eligibleEvents) {
                // Vérifier que l'événement n'est pas ignoré
                if (matchmakingBO.isEventIgnored(personaId, event.getId())) {
                    logger.debug("Event {} ignored by PersonaId={}, skipping", event.getId(), personaId);
                    continue;
                }
                
                // Vérifier les restrictions de voiture si elles existent
                if (event.getCarRestriction() != null && !event.getCarRestriction().trim().isEmpty()) {
                    if (!eventBO.hasAllowedCarForEvent(personaId, event)) {
                        logger.debug("PersonaId={} does not have allowed car for Event {}, skipping", personaId, event.getId());
                        continue;
                    }
                }
                
                validEvents.add(event);
            }
            
            if (validEvents.isEmpty()) {
                logger.warn("No valid events found for auto-lobby creation after filtering (PersonaId={})", personaId);
                return false;
            }
            
            // Sélectionner aléatoirement un événement parmi les valides
            java.util.Random random = new java.util.Random();
            EventEntity selectedEvent = validEvents.get(random.nextInt(validEvents.size()));
            
            logger.info("Creating auto-lobby for PersonaId={} on Event {} ({}) - CarClass={}", 
                personaId, selectedEvent.getId(), selectedEvent.getName(), carClassHash);
            
            // Créer le lobby via LobbyBO (lobby public)
            LobbyEntity createdLobby = lobbyBO.createLobby(personaId, selectedEvent.getId(), carClassHash, false);
            
            if (createdLobby != null) {
                logger.info("Auto-lobby created successfully: LobbyId={}, EventId={}, PersonaId={}", 
                    createdLobby.getId(), selectedEvent.getId(), personaId);
                return true;
            } else {
                logger.error("Failed to create auto-lobby for PersonaId={}", personaId);
                return false;
            }
            
        } catch (Exception e) {
            logger.error("Error creating auto-lobby for PersonaId={}: {}", personaId, e.getMessage(), e);
            return false;
        }
    }
    
    @PreDestroy
    public void cleanup() {
        // RaceNow monitor shutting down
    }
}
