/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.bo;
import javax.ejb.Asynchronous;
import javax.ejb.Schedule;
import javax.ejb.Singleton;
import javax.ejb.Startup;

import com.soapboxrace.core.events.PersonaPresenceUpdated;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.slf4j.Logger;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.event.Event;
import javax.inject.Inject;
import java.util.List;

@Startup
@Singleton
public class PresenceBO {
    // Constantes pour les états de présence
    public static final Long PRESENCE_OFFLINE = 0L;
    public static final Long PRESENCE_ONLINE = 1L;
    public static final Long PRESENCE_IN_RACE = 2L;

    @Inject
    private RedisBO redisBO;

    @Inject
    private ParameterBO parameterBO;

    @Inject
    private Logger logger;

    @Inject
    private Event<PersonaPresenceUpdated> personaPresenceUpdatedEvent;

    // Redis utilisé UNIQUEMENT pour l'état "in race" (XMPP ne fait pas cette distinction)
    private StatefulRedisPubSubConnection<String, String> pubSubConnection;
    private StatefulRedisConnection<String, String> connection;

    @PostConstruct
    public void init() {
        if (this.parameterBO.getBoolParam("ENABLE_REDIS")) {
            this.pubSubConnection = this.redisBO.createPubSub();
            this.connection = this.redisBO.getConnection();
            logger.info("Initialized presence system");
        } else {
            logger.warn("Redis is not enabled! Presence system is disabled.");
        }
    }

    @PreDestroy
    public void shutdown() {
        if (this.connection != null) {
            List<String> keys = this.connection.sync().keys("game_presence.*");
            if (!keys.isEmpty())
                this.connection.sync().del(keys.toArray(new String[0]));
            this.pubSubConnection.close();
        }
    }

    @Asynchronous
    public void updatePresence(Long personaId, Long presence) {
        if (this.connection != null && !personaId.equals(0L)) {
            Long currentPresence = getPresence(personaId);
            logger.debug("Updating presence for persona {} from {} to {}", personaId, currentPresence, presence);

            if (!currentPresence.equals(presence)) {
                // Validation de la transition
                if (!isValidTransition(currentPresence, presence, personaId)) {
                    logger.warn("Invalid presence transition for persona {}: {} -> {}. Transition blocked.", 
                               personaId, getPresenceDescription(currentPresence), getPresenceDescription(presence));
                    return;
                }
                
                this.connection.sync().setex(
                    getPresenceKey(personaId),
                    this.parameterBO.getIntParam("SBRWR_PRESENCEEXPIRATIONTIME", 300),
                    presence.toString()
                );
                if (this.pubSubConnection != null) {
                    this.pubSubConnection.sync().publish("game_presence_updates", personaId + "|" + presence);
                }
                this.personaPresenceUpdatedEvent.fire(new PersonaPresenceUpdated(personaId, presence));
                logger.info("Presence updated for persona {}: {} -> {}", personaId, 
                           getPresenceDescription(currentPresence), getPresenceDescription(presence));
            } else {
                logger.debug("Presence unchanged for persona {}: {}", personaId, getPresenceDescription(presence));
            }
        } else {
            logger.warn("Cannot update presence: Redis connection is null or invalid personaId: {}", personaId);
        }
    }

    /**
     * Valide si une transition de statut est autorisée.
     * Empêche les transitions invalides qui pourraient corrompre l'état du joueur.
     * 
     * @param currentPresence État actuel
     * @param newPresence Nouvel état souhaité
     * @param personaId ID du persona (pour les logs)
     * @return true si la transition est valide
     */
    private boolean isValidTransition(Long currentPresence, Long newPresence, Long personaId) {
        // Toutes les transitions vers OFFLINE sont autorisées (déconnexion d'urgence)
        if (PRESENCE_OFFLINE.equals(newPresence)) {
            return true;
        }
        
        // OFFLINE -> ONLINE : Connexion normale (OK)
        if (PRESENCE_OFFLINE.equals(currentPresence) && PRESENCE_ONLINE.equals(newPresence)) {
            return true;
        }
        
        // ONLINE -> IN_RACE : Le joueur rejoint une course (OK)
        if (PRESENCE_ONLINE.equals(currentPresence) && PRESENCE_IN_RACE.equals(newPresence)) {
            return true;
        }
        
        // IN_RACE -> ONLINE : Le joueur termine/abandonne une course (OK)
        if (PRESENCE_IN_RACE.equals(currentPresence) && PRESENCE_ONLINE.equals(newPresence)) {
            return true;
        }
        
        // IN_RACE -> IN_RACE : Rafraîchissement pendant la course (OK)
        if (PRESENCE_IN_RACE.equals(currentPresence) && PRESENCE_IN_RACE.equals(newPresence)) {
            return true;
        }
        
        // ONLINE -> ONLINE : Rafraîchissement normal (OK)
        if (PRESENCE_ONLINE.equals(currentPresence) && PRESENCE_ONLINE.equals(newPresence)) {
            return true;
        }
        
        // OFFLINE -> IN_RACE : INVALIDE ! Le joueur ne peut pas passer directement de déconnecté à en course
        if (PRESENCE_OFFLINE.equals(currentPresence) && PRESENCE_IN_RACE.equals(newPresence)) {
            logger.error("CRITICAL: Attempted invalid transition OFFLINE -> IN_RACE for persona {}", personaId);
            return false;
        }
        
        // Toute autre transition est considérée comme suspecte
        logger.warn("Suspicious presence transition for persona {}: {} -> {}", 
                   personaId, getPresenceDescription(currentPresence), getPresenceDescription(newPresence));
        return false;
    }

    /**
     * Refreshes persona's presence expiration time. This should be called regularly for all online personas.
     * FIX: Augmenté le TTL par défaut et amélioré les logs
     * @param personaId Persona ID whose presence to refresh
     */
    public void refreshPresence(long personaId) {
        if (this.connection != null) {
            String presenceKey = getPresenceKey(personaId);
            Long ttl = this.connection.sync().ttl(presenceKey);
            
            if (ttl != null && ttl > 0) {
                this.connection.sync().expire(presenceKey, this.parameterBO.getIntParam("SBRWR_PRESENCEEXPIRATIONTIME", 300));
                logger.debug("Refreshed presence TTL for persona {}, was {} seconds", personaId, ttl);
            } else {
                logger.debug("Presence key missing for persona {}, not restoring automatically", personaId);
            }
        }
    }

    /**
     * Rafraîchit la présence d'un joueur seulement s'il a déjà une présence active.
     * Cette méthode évite de recréer une présence pour des joueurs qui se sont déconnectés.
     * FIX: Augmenté le TTL et amélioré les logs
     * @param personaId ID du persona
     * @return true si la présence a été rafraîchie, false sinon
     */
    public boolean refreshPresenceIfExists(long personaId) {
        if (this.connection != null) {
            String presenceKey = getPresenceKey(personaId);
            Long ttl = this.connection.sync().ttl(presenceKey);
            
            if (ttl != null && ttl > 0) {
                this.connection.sync().expire(presenceKey, this.parameterBO.getIntParam("SBRWR_PRESENCEEXPIRATIONTIME", 300));
                logger.debug("Refreshed presence TTL for persona {}, was {} seconds", personaId, ttl);
                return true;
            } else {
                logger.debug("Presence key missing for persona {}, not refreshing", personaId);
                return false;
            }
        }
        return false;
    }

    public void removePresence(Long personaId) {
        if (this.connection != null && !personaId.equals(0L)) {
            logger.info("Removing presence for persona {}", personaId);
            updatePresence(personaId, PRESENCE_OFFLINE);
            this.connection.sync().del(getPresenceKey(personaId));
        } else {
            logger.warn("Cannot remove presence: invalid personaId {} or Redis connection null", personaId);
        }
    }

    /**
     * Met à jour la présence d'un joueur comme étant en ligne (dans le safehouse)
     * @param personaId ID du persona
     */
    public void setPresenceOnline(Long personaId) {
        updatePresence(personaId, PRESENCE_ONLINE);
        logger.debug("Set persona {} presence to ONLINE", personaId);
    }

    /**
     * Met à jour la présence d'un joueur comme étant en course
     * @param personaId ID du persona
     */
    public void setPresenceInRace(Long personaId) {
        updatePresence(personaId, PRESENCE_IN_RACE);
        logger.info("Set persona {} presence to IN_RACE", personaId);
    }

    /**
     * Met à jour la présence d'un joueur comme étant hors ligne
     * @param personaId ID du persona
     */
    public void setPresenceOffline(Long personaId) {
        removePresence(personaId);
    }

    /**
     * Force la mise hors ligne d'un joueur, même s'il était en course.
     * Cette méthode contourne les validations de transition et doit être utilisée
     * uniquement pour les déconnexions d'urgence (timeout, crash, ban, etc.)
     * @param personaId ID du persona
     */
    public void forcePresenceOffline(Long personaId) {
        if (this.connection != null && !personaId.equals(0L)) {
            Long currentPresence = getPresence(personaId);
            logger.warn("Forcing presence offline for persona {} (was: {})", 
                       personaId, getPresenceDescription(currentPresence));
            
            // Supprimer directement la clé Redis sans passer par updatePresence
            this.connection.sync().del(getPresenceKey(personaId));
            
            // Publier la notification de déconnexion
            if (this.pubSubConnection != null) {
                this.pubSubConnection.sync().publish("game_presence_updates", personaId + "|" + PRESENCE_OFFLINE);
            }
            
            // Déclencher l'événement de changement de présence
            this.personaPresenceUpdatedEvent.fire(new PersonaPresenceUpdated(personaId, PRESENCE_OFFLINE));
            
            logger.info("Forcefully set persona {} to OFFLINE (bypassed transition validation)", personaId);
        } else {
            logger.warn("Cannot force presence offline: invalid personaId {} or Redis connection null", personaId);
        }
    }

    /**
     * Vérifie si un joueur est actuellement en course
     * @param personaId ID du persona
     * @return true si le joueur est en course
     */
    public boolean isPlayerInRace(Long personaId) {
        return PRESENCE_IN_RACE.equals(getPresence(personaId));
    }

    /**
     * Vérifie si un joueur est en ligne (dans le safehouse)
     * @param personaId ID du persona
     * @return true si le joueur est en ligne mais pas en course
     */
    public boolean isPlayerOnline(Long personaId) {
        return PRESENCE_ONLINE.equals(getPresence(personaId));
    }

    /**
     * Vérifie si un persona est en ligne et disponible via la présence Redis.
     */
    public boolean isPersonaOnlineAndAvailable(Long personaId) {
        return personaId != null && !personaId.equals(0L) && !PRESENCE_OFFLINE.equals(getPresence(personaId));
    }

    /**
     * Retourne une description textuelle de l'état de présence
     * @param presence valeur numérique de la présence
     * @return description textuelle
     */
    public String getPresenceDescription(Long presence) {
        if (PRESENCE_OFFLINE.equals(presence)) {
            return "Offline";
        } else if (PRESENCE_ONLINE.equals(presence)) {
            return "Online (Safehouse)";
        } else if (PRESENCE_IN_RACE.equals(presence)) {
            return "In Race";
        } else {
            return "Unknown (" + presence + ")";
        }
    }

    public Long getPresence(Long personaId) {
        if (this.connection == null) {
            logger.warn("Cannot get presence for persona {}: Redis connection is null", personaId);
            return PRESENCE_OFFLINE;
        }

        String value = this.connection.sync().get(getPresenceKey(personaId));

        if (value == null || value.trim().isEmpty()) {
            logger.debug("No presence found for persona {}, returning offline", personaId);
            return PRESENCE_OFFLINE;
        }

        try {
            Long presence = Long.parseLong(value);
            logger.debug("Retrieved presence for persona {}: {}", personaId, presence);
            return presence;
        } catch (NumberFormatException e) {
            logger.error("Invalid presence value '{}' for persona {}, returning offline", value, personaId);
            return PRESENCE_OFFLINE;
        }
    }

    private String getPresenceKey(Long personaId) {
        return "game_presence." + personaId;
    }

    /**
     * Nettoie et vérifie la cohérence des présences. À appeler périodiquement.
     * FIX CRITIQUE: Envoie maintenant des notifications OFFLINE pour les déconnexions silencieuses (crash/kill)
     * Cette méthode identifie les personas qui devraient être en ligne mais qui apparaissent hors ligne.
     */
    @Schedule(minute = "*/2", hour = "*", persistent = false)
    public void cleanupPresenceInconsistencies() {
        if (this.connection == null) {
            logger.debug("Skipping presence cleanup: Redis not available");
            return;
        }

        logger.debug("Starting presence cleanup task");
        
        try {
            // Récupérer toutes les clés de présence actives
            List<String> activeKeys = this.connection.sync().keys("game_presence.*");
            
            if (activeKeys.isEmpty()) {
                logger.debug("No active presence keys found");
                return;
            }
            
            int expiredCount = 0;
            int activeCount = 0;
            
            for (String key : activeKeys) {
                Long ttl = this.connection.sync().ttl(key);
                String personaIdStr = key.substring("game_presence.".length());
                
                try {
                    Long personaId = Long.parseLong(personaIdStr);

                    if (ttl != null && ttl <= 30L) {
                        logger.warn("Presence key {} for persona {} has low TTL: {} seconds", key, personaId, ttl);
                    }

                    if (ttl != null && ttl > 0) {
                        activeCount++;
                    } else {
                        expiredCount++;
                        logger.info("Removing expired presence key: {}", key);
                        this.connection.sync().del(key);
                    }
                } catch (NumberFormatException e) {
                    logger.warn("Invalid persona ID in presence key: {}", key);
                }
            }

            logger.debug("Presence cleanup completed: {} active, {} expired/removed", activeCount, expiredCount);
            
        } catch (Exception e) {
            logger.error("Error during presence cleanup", e);
        }
    }

    /**
     * Retourne un résumé de l'état actuel des présences pour le debugging
     */
    public String getPresenceDebugInfo() {
        if (this.connection == null) {
            return "Presence system disabled (Redis not available)";
        }

        try {
            List<String> activeKeys = this.connection.sync().keys("game_presence.*");
            StringBuilder info = new StringBuilder();
            info.append("Presence Debug Info:\n");
            info.append("Total active presence keys: ").append(activeKeys.size()).append("\n");
            
            // Statistiques par état
            int onlineCount = 0;
            int inRaceCount = 0;
            int unknownCount = 0;
            int lowTTLCount = 0;
            
            if (!activeKeys.isEmpty()) {
                for (String key : activeKeys) {
                    String value = this.connection.sync().get(key);
                    if (value != null) {
                        Long presenceValue = Long.parseLong(value);
                        Long ttl = this.connection.sync().ttl(key);
                        
                        if (PRESENCE_ONLINE.equals(presenceValue)) {
                            onlineCount++;
                        } else if (PRESENCE_IN_RACE.equals(presenceValue)) {
                            inRaceCount++;
                        } else {
                            unknownCount++;
                        }
                        
                        if (ttl != null && ttl <= 30L) {
                            lowTTLCount++;
                        }
                    }
                }
                
                info.append("\nStatistics by status:\n");
                info.append("  Online (Safehouse): ").append(onlineCount).append("\n");
                info.append("  In Race: ").append(inRaceCount).append("\n");
                info.append("  Unknown/Other: ").append(unknownCount).append("\n");
                info.append("  Low TTL (<30s): ").append(lowTTLCount).append("\n");
                
                info.append("\nDetailed presences:\n");
                for (String key : activeKeys) {
                    String personaIdStr = key.substring("game_presence.".length());
                    String value = this.connection.sync().get(key);
                    Long ttl = this.connection.sync().ttl(key);
                    Long presenceValue = value != null ? Long.parseLong(value) : 0L;
                    
                    String ttlWarning = (ttl != null && ttl <= 30L) ? " [LOW TTL WARNING]" : "";
                    info.append("  Persona ").append(personaIdStr).append(": ")
                        .append(getPresenceDescription(presenceValue))
                        .append(" (TTL: ").append(ttl).append("s)").append(ttlWarning).append("\n");
                }
            }
            
            return info.toString();
        } catch (Exception e) {
            return "Error retrieving presence debug info: " + e.getMessage();
        }
    }
}