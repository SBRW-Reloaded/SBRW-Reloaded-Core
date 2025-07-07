/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.bo;

import com.soapboxrace.core.events.PersonaPresenceUpdated;
import io.lettuce.core.KeyValue;
import io.lettuce.core.ScanIterator;
import io.lettuce.core.api.StatefulRedisConnection;
import org.slf4j.Logger;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.ejb.*;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.soapboxrace.core.jpa.EventEntity;
import com.soapboxrace.core.xmpp.XmppChat;
import com.soapboxrace.core.xmpp.OpenFireSoapBoxCli;


/**
 * Responsible for managing the multiplayer matchmaking system.
 * This deals with 2 classes of events: restricted and open.
 * When asked for a persona for a given car class, the matchmaker
 * will check if that class is open or restricted. Open events will receive
 * players of any class, while restricted events will only receive players of
 * the required class.
 *
 * @author heyitsleo
 */
@Singleton
@Startup
@Lock(LockType.READ)
public class MatchmakingBO {

    @EJB
    private RedisBO redisBO;

    @EJB
    private ParameterBO parameterBO;

    @EJB
	private OpenFireSoapBoxCli openFireSoapBoxCli;

    @Inject
    private Logger logger;

    private StatefulRedisConnection<String, String> redisConnection;

    @PostConstruct
    public void initialize() {
        if (this.parameterBO.getBoolParam("ENABLE_REDIS")) {
            this.redisConnection = this.redisBO.getConnection();
            logger.info("Initialized matchmaking system");
        } else {
            logger.warn("Redis is not enabled! Matchmaking queue is disabled.");
        }
    }

    @PreDestroy
    public void shutdown() {
        if (this.redisConnection != null) {
            this.redisConnection.sync().del("matchmaking_queue");
        }
        logger.info("Shutdown matchmaking system");
    }

    /**
     * Adds the given persona ID to the queue under the given car class.
     *
     * @param personaId The ID of the persona to add to the queue.
     * @param carClass  The class of the persona's current car.
     */
    public void addPlayerToQueue(Long personaId, Integer carClass) {
        if (this.redisConnection != null) {
            this.redisConnection.sync().hset("matchmaking_queue", personaId.toString(), carClass.toString());
        }
    }

    /**
     * Removes the given persona ID from the queue.
     *
     * @param personaId The ID of the persona to remove from the queue.
     */
    public void removePlayerFromQueue(Long personaId) {
        if (this.redisConnection != null) {
            this.redisConnection.sync().hdel("matchmaking_queue", personaId.toString());
        }
    }

    /**
     * Gets the ID of a persona from the queue, as long as that persona is listed under the given car class.
     *
     * @param carClass The car class hash to find a persona in.
     * @return The ID of the persona, or {@literal -1} if no persona was found.
     */
    public Long getPlayerFromQueue(Integer carClass) {
        if (this.redisConnection == null)
            return -1L;

        ScanIterator<KeyValue<String, String>> iterator = ScanIterator.hscan(this.redisConnection.sync(), "matchmaking_queue");
        long personaId = -1L;

        while (iterator.hasNext()) {
            KeyValue<String, String> keyValue = iterator.next();

            if (carClass == 607077938 || Integer.parseInt(keyValue.getValue()) == carClass) {
                personaId = Long.parseLong(keyValue.getKey());
                break;
            }
        }

        return personaId;
    }

    /**
     * Add the given event ID to the list of ignored events for the given persona ID.
     *
     * @param personaId     the persona ID
     * @param EventEntity   the eventEntity
     */
    public void ignoreEvent(long personaId, EventEntity EventEntity) {
        if (this.redisConnection != null) {
        	if (this.redisConnection.sync().hexists("matchmaking_queue", Long.toString(personaId))) {
        		this.redisConnection.sync().sadd("ignored_events." + personaId, Long.toString(EventEntity.getId()));
                openFireSoapBoxCli.send(XmppChat.createSystemMessage("SBRWR_MATCHMAKING_IGNOREDEVENT," + EventEntity.getName()), personaId);
        	}
        }
    }

    /**
     * Resets the list of ignored events for the given persona ID
     *
     * @param personaId the persona ID
     */
    public void resetIgnoredEvents(long personaId) {
        if (this.redisConnection != null) {
            this.redisConnection.sync().del("ignored_events." + personaId);
        }
    }

    /**
     * Checks if the given event ID is in the list of ignored events for the given persona ID
     *
     * @param personaId the persona ID
     * @param eventId   the event ID
     * @return {@code true} if the given event ID is in the list of ignored events for the given persona ID
     */
    public boolean isEventIgnored(long personaId, long eventId) {
        if (this.redisConnection != null) {
            return this.redisConnection.sync().sismember("ignored_events." + personaId, Long.toString(eventId));
        }

        return false;
    }

    /**
     * Adds a player to the persistent RaceNow queue
     *
     * @param personaId The ID of the persona to add to the RaceNow queue.
     * @param carClass  The class of the persona's current car.
     * @param level     The level of the persona.
     */
    public void addPlayerToRaceNowQueue(Long personaId, Integer carClass, Integer level) {
        if (this.redisConnection != null) {
            // Stocker les informations du joueur dans une hash map Redis dédiée à RaceNow
            String raceNowKey = "racenow_queue:" + personaId;
            Map<String, String> data = new HashMap<>();
            data.put("carClass", carClass.toString());
            data.put("level", level.toString());
            data.put("timestamp", String.valueOf(System.currentTimeMillis()));
            
            this.redisConnection.sync().hmset(raceNowKey, data);
            
            // Ajouter à un set pour un accès rapide
            this.redisConnection.sync().sadd("racenow_active_players", personaId.toString());
            
            logger.info("Added PersonaId={} to persistent RaceNow queue (CarClass={}, Level={})", 
                personaId, carClass, level);
        }
    }

    /**
     * Removes a player from the persistent RaceNow queue
     *
     * @param personaId The ID of the persona to remove from the RaceNow queue.
     */
    public void removePlayerFromRaceNowQueue(Long personaId) {
        if (this.redisConnection != null) {
            this.redisConnection.sync().del("racenow_queue:" + personaId);
            this.redisConnection.sync().srem("racenow_active_players", personaId.toString());
            logger.info("Removed PersonaId={} from persistent RaceNow queue", personaId);
        }
    }

    /**
     * Checks if a player is in the persistent RaceNow queue
     *
     * @param personaId The ID of the persona to check.
     * @return true if the player is in the RaceNow queue
     */
    public boolean isPlayerInRaceNowQueue(Long personaId) {
        if (this.redisConnection != null) {
            return this.redisConnection.sync().sismember("racenow_active_players", personaId.toString());
        }
        return false;
    }

    /**
     * Gets all players currently in the RaceNow queue
     *
     * @return Set of persona IDs in the RaceNow queue
     */
    public Set<String> getRaceNowQueuePlayers() {
        if (this.redisConnection != null) {
            return this.redisConnection.sync().smembers("racenow_active_players");
        }
        return new HashSet<>();
    }

    /**
     * Gets the RaceNow queue data for a specific player
     *
     * @param personaId The ID of the persona
     * @return Map containing carClass, level, timestamp or null if not found
     */
    public Map<String, String> getRaceNowQueueData(Long personaId) {
        if (this.redisConnection != null) {
            return this.redisConnection.sync().hgetall("racenow_queue:" + personaId);
        }
        return null;
    }

    @Asynchronous
    @Lock(LockType.READ)
    public void handlePersonaPresenceUpdated(@Observes PersonaPresenceUpdated personaPresenceUpdated) {
        removePlayerFromQueue(personaPresenceUpdated.getPersonaId());
        // Nettoyer aussi la file RaceNow quand le joueur se déconnecte
        removePlayerFromRaceNowQueue(personaPresenceUpdated.getPersonaId());
    }
}
