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
    
    @PostConstruct
    public void initialize() {
        try {
            logger.info("========== INITIALIZING RACENOW MONITOR ==========");
            
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
                logger.info("RaceNow monitor interval configured from database: {}s", configInterval);
            } catch (Exception e) {
                logger.warn("Could not read SBRWR_RACENOW_MONITOR_INTERVAL parameter, using default 5s", e);
            }
            
            timerService.createIntervalTimer(initialDelay, interval, timerConfig);
            
            logger.info("RaceNow monitor initialized successfully:");
            logger.info("  - Initial delay: {}ms", initialDelay);
            logger.info("  - Interval: {}ms ({}s)", interval, interval/1000);
            logger.info("  - Timer service: {}", timerService.getClass().getSimpleName());
            logger.info("====================================================");
            
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
                logger.info("========== RACENOW MONITOR HEARTBEAT #{} ==========", executionCounter);
                logger.info("Timer execution at: {}", new java.util.Date());
                logger.info("Timer info: {}", timer.getInfo());
            } else {
                logger.debug("========== RACENOW MONITOR TICK #{} ==========", executionCounter);
            }
            
            // Vérifier si le système RaceNow persistant est activé
            boolean isEnabled = true; // Par défaut activé
            try {
                isEnabled = parameterBO.getBoolParam("SBRWR_RACENOW_PERSISTENT_ENABLED");
                if (shouldLogDetails) {
                    logger.info("RaceNow persistent system enabled: {}", isEnabled);
                }
            } catch (Exception e) {
                logger.warn("Could not read SBRWR_RACENOW_PERSISTENT_ENABLED parameter, assuming enabled", e);
            }
            
            if (!isEnabled) {
                logger.info("RaceNow persistent system is disabled, skipping monitoring");
                return; // Système désactivé
            }
            
            // Récupérer tous les joueurs en attente RaceNow
            Set<String> playersInQueue = matchmakingBO.getRaceNowQueuePlayers();
            
            // Debug: Afficher les détails de la queue récupérée
            if (!playersInQueue.isEmpty() || shouldLogDetails) {
                logger.info("RACENOW MONITOR: Retrieved {} players from queue: {}", 
                    playersInQueue.size(), playersInQueue);
            }
            
            // Vérifier s'il y a des joueurs à scanner immédiatement
            Set<String> immediateScanPlayers = matchmakingBO.getAndClearImmediateScanPlayers();
            
            // Combiner les joueurs en attente normale et ceux demandant un scan immédiat
            Set<String> allPlayersToProcess = new HashSet<>(playersInQueue);
            if (!immediateScanPlayers.isEmpty()) {
                // Ajouter les joueurs du scan immédiat (éviter les doublons avec addAll)
                allPlayersToProcess.addAll(immediateScanPlayers);
                logger.info("Processing {} immediate scan requests for players: {}", 
                    immediateScanPlayers.size(), immediateScanPlayers);
            }
            
            // Si il y a des joueurs en file, toujours logger les détails
            if (!allPlayersToProcess.isEmpty()) {
                shouldLogDetails = true;
                logger.info("========== RACENOW MONITOR ACTIVE #{} ==========", executionCounter);
            }
            
            logger.info("RaceNow queue status: {} players waiting ({} immediate scans)", 
                playersInQueue.size(), immediateScanPlayers.size());
            
            if (allPlayersToProcess.isEmpty()) {
                if (shouldLogDetails) {
                    logger.info("No players in RaceNow queue, nothing to do");
                }
                return; // Rien à faire
            }
            
            logger.info("Processing {} total players ({} in queue + {} immediate):", 
                allPlayersToProcess.size(), playersInQueue.size(), immediateScanPlayers.size());
            
            int playersProcessed = 0;
            for (String personaIdStr : allPlayersToProcess) {
                try {
                    Long personaId = Long.parseLong(personaIdStr);
                    logger.info("  [{}] Processing PersonaId={}", ++playersProcessed, personaId);
                    checkForAvailableLobbies(personaId);
                } catch (NumberFormatException e) {
                    logger.warn("Invalid persona ID in RaceNow queue: {}", personaIdStr);
                    try {
                        matchmakingBO.removePlayerFromRaceNowQueue(Long.parseLong(personaIdStr));
                        logger.info("Removed invalid PersonaId from queue: {}", personaIdStr);
                    } catch (Exception ex) {
                        logger.error("Failed to remove invalid persona ID from queue: {}", personaIdStr);
                    }
                }
            }
            
            long duration = System.currentTimeMillis() - startTime;
            logger.info("RaceNow monitor cycle completed in {}ms - processed {} players", duration, playersProcessed);
            logger.info("========================================");
            
        } catch (Exception e) {
            logger.error("CRITICAL ERROR in RaceNow monitor (execution #{}): {}", executionCounter, e.getMessage(), e);
            logger.error("Stack trace:", e);
        } finally {
            long totalDuration = System.currentTimeMillis() - startTime;
            if (executionCounter % 10 == 0 || totalDuration > 1000) { // Log si c'est un heartbeat ou si l'exécution prend du temps
                logger.info("RaceNow monitor tick #{} finished (total time: {}ms)", executionCounter, totalDuration);
            } else {
                logger.debug("RaceNow monitor tick #{} finished (total time: {}ms)", executionCounter, totalDuration);
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
                logger.debug("No queue data found for PersonaId={}, removing from queue", personaId);
                matchmakingBO.removePlayerFromRaceNowQueue(personaId);
                return;
            }
            
            Integer carClassHash = Integer.parseInt(queueData.get("carClass"));
            Integer playerLevel = Integer.parseInt(queueData.get("level"));
            Long timestamp = Long.parseLong(queueData.get("timestamp"));
            
            logger.debug("Checking lobbies for PersonaId={} (CarClass={}, Level={})", personaId, carClassHash, playerLevel);
            
            // Vérifier que le joueur n'attend pas depuis trop longtemps (éviter les timeouts)
            long maxWaitTime = 30 * 60 * 1000; // 30 minutes par défaut
            try {
                maxWaitTime = parameterBO.getIntParam("SBRWR_RACENOW_MAX_WAIT_MINUTES") * 60 * 1000;
            } catch (Exception e) {
                logger.warn("Could not read SBRWR_RACENOW_MAX_WAIT_MINUTES parameter, using default 30min", e);
            }
            if (System.currentTimeMillis() - timestamp > maxWaitTime) {
                logger.info("RaceNow timeout for PersonaId={} after {}min, removing from queue", 
                    personaId, (System.currentTimeMillis() - timestamp) / (60 * 1000));
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
                    logger.warn("RACENOW_MONITOR: SECURITY - SQL query returned inappropriate lobby: PersonaId={} (Level={}) got EventId={} (MinLevel={}, MaxLevel={})", 
                               personaId, playerLevel, event.getId(), event.getMinLevel(), event.getMaxLevel());
                }
            }
            
            if (levelVerifiedLobbies.size() != availableLobbies.size()) {
                logger.error("RACENOW_MONITOR: CRITICAL - SQL level filtering failed! Expected {} lobbies, got {} after level verification", 
                            levelVerifiedLobbies.size(), availableLobbies.size());
            }
            
            logger.debug("Found {} level-verified lobbies for PersonaId={} (was {} from SQL)", levelVerifiedLobbies.size(), personaId, availableLobbies.size());
            
            if (!levelVerifiedLobbies.isEmpty()) {
                PersonaEntity personaEntity = personaDAO.find(personaId);
                if (personaEntity != null) {
                    logger.info("Found {} available lobbies for RaceNow PersonaId={}, attempting to join directly", 
                        levelVerifiedLobbies.size(), personaId);
                    
                    // Faire rejoindre directement le joueur au lieu d'envoyer juste une invitation
                    boolean joinedSuccessfully = attemptToJoinLobbyDirectly(personaEntity, levelVerifiedLobbies);
                    
                    if (joinedSuccessfully) {
                        matchmakingBO.removePlayerFromRaceNowQueue(personaId);
                        logger.info("PersonaId={} successfully auto-joined lobby via RaceNow monitor", personaId);
                    } else {
                        logger.debug("PersonaId={} could not join any lobby, staying in queue", personaId);
                    }
                } else {
                    logger.warn("PersonaEntity not found for PersonaId={}, removing from RaceNow queue", personaId);
                    matchmakingBO.removePlayerFromRaceNowQueue(personaId);
                }
            } else {
                logger.debug("No available lobbies found for PersonaId={}", personaId);
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
                    logger.debug("PersonaId={} level {} not compatible with EventId={} (min={}, max={})", 
                        personaEntity.getPersonaId(), personaEntity.getLevel(), event.getId(), event.getMinLevel(), event.getMaxLevel());
                    continue;
                }
                
                // Vérifier si l'événement est ignoré
                if (matchmakingBO.isEventIgnored(personaEntity.getPersonaId(), event.getId())) {
                    logger.debug("PersonaId={} has ignored EventId={}", personaEntity.getPersonaId(), event.getId());
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
                            
                            logger.info("RaceNow auto-invitation sent: PersonaId={} invited to LobbyId={} (EventId={}, {}/{} players)", 
                                personaEntity.getPersonaId(), lobbyEntity.getId(), event.getId(), currentEntrants, maxEntrants);
                            
                            return true; // Invitation envoyée avec succès
                            
                        } catch (Exception e) {
                            logger.error("Failed to join PersonaId={} to LobbyId={}: {}", 
                                personaEntity.getPersonaId(), lobbyEntity.getId(), e.getMessage(), e);
                            continue; // Essayer le lobby suivant
                        }
                    } else {
                        logger.debug("PersonaId={} already in LobbyId={}", personaEntity.getPersonaId(), lobbyEntity.getId());
                    }
                } else {
                    logger.debug("LobbyId={} is full ({}/{})", lobbyEntity.getId(), currentEntrants, maxEntrants);
                }
            }
            
            return false; // Aucun lobby disponible trouvé
            
        } catch (Exception e) {
            logger.error("Error attempting to join lobby for PersonaId={}: {}", 
                personaEntity.getPersonaId(), e.getMessage(), e);
            return false;
        }
    }
    
    @PreDestroy
    public void cleanup() {
        logger.info("RaceNow monitor shutting down");
    }
}
