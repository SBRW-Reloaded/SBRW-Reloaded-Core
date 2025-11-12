package com.soapboxrace.core.bo;

import com.soapboxrace.core.bo.util.AchievementEventContext;
import com.soapboxrace.core.dao.*;
import com.soapboxrace.core.jpa.*;
import com.soapboxrace.jaxb.http.ArbitrationPacket;
import com.soapboxrace.jaxb.http.ClientPhysicsMetrics;
import com.soapboxrace.jaxb.http.EventResult;
import com.soapboxrace.jaxb.http.ExitPath;
import com.soapboxrace.core.bo.util.OwnedCarConverter;
import com.soapboxrace.jaxb.util.JAXBUtility;
import com.soapboxrace.core.bo.util.HelpingTools;

import javax.ejb.EJB;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for {@link ArbitrationPacket} -> {@link EventResult} converters
 *
 * @param <TA> The type of {@link ArbitrationPacket} that this converter accepts
 * @param <TR> The type of {@link EventResult} that this converter produces
 */
public abstract class EventResultBO<TA extends ArbitrationPacket, TR extends EventResult> {

    private static final Logger logger = LoggerFactory.getLogger(EventResultBO.class);

    @EJB
    protected MatchmakingBO matchmakingBO;

    @EJB
    protected LobbyBO lobbyBO;

    @EJB
    protected ParameterBO parameterBo;

    @EJB
    protected PersonaBO personaBO;

    @EJB
    protected PersonaDAO personaDAO;

    @EJB
    private CarDAO carDAO;

    @EJB
    private EventDataSetupDAO eventDataSetupDAO;

    @EJB
    private EventDataDAO eventDataDAO;

    @EJB
    protected AchievementBO achievementBO;

    @EJB
    protected EventDAO eventDAO;

    @EJB
    protected LobbyDAO lobbyDAO;

    @EJB
    protected EventSessionDAO eventSessionDAO;
    
    @EJB
    protected RaceAgainBO raceAgainBO;
    
    /**
     * Converts the given {@link TA} instance to a new {@link TR} instance.
     *
     * @param eventSessionEntity The {@link EventSessionEntity} associated with the arbitration packet
     * @param activePersonaId    The ID of the current persona
     * @param packet             The {@link TA} instance
     * @return new {@link TR} instance
     */
    public TR handle(EventSessionEntity eventSessionEntity, Long activePersonaId, TA packet) {
        logger.info("@@@ Thread {} - handle() called for EventSession {}, PersonaId {}, finishReason={}", 
            Thread.currentThread().getName(), 
            eventSessionEntity != null ? eventSessionEntity.getId() : "null", 
            activePersonaId,
            packet.getFinishReason());
        
        if(parameterBo.getBoolParam("SBRWR_DISABLE_1_REPORTS")) packet.setHacksDetected(packet.getHacksDetected() & ~1);
        if(parameterBo.getBoolParam("SBRWR_DISABLE_2_REPORTS")) packet.setHacksDetected(packet.getHacksDetected() & ~2);
        if(parameterBo.getBoolParam("SBRWR_DISABLE_4_REPORTS")) packet.setHacksDetected(packet.getHacksDetected() & ~4);
        if(parameterBo.getBoolParam("SBRWR_DISABLE_8_REPORTS")) packet.setHacksDetected(packet.getHacksDetected() & ~8);
        if(parameterBo.getBoolParam("SBRWR_DISABLE_16_REPORTS")) packet.setHacksDetected(packet.getHacksDetected() & ~16);
        if(parameterBo.getBoolParam("SBRWR_DISABLE_32_REPORTS")) packet.setHacksDetected(packet.getHacksDetected() & ~32);
              
        return handleInternal(eventSessionEntity, activePersonaId, packet);
    }

    /**
     * Internal method to convert the given {@link TA} instance to a new {@link TR} instance.
     *
     * @param eventSessionEntity The {@link EventSessionEntity} associated with the arbitration packet
     * @param activePersonaId    The ID of the current persona
     * @param packet             The {@link TA} instance
     * @return new {@link TR} instance
     */
    protected abstract TR handleInternal(EventSessionEntity eventSessionEntity, Long activePersonaId, TA packet);

    /**
     * Sets some basic properties of the given {@link EventDataEntity}
     *
     * @param eventDataEntity the {@link EventDataEntity} instance
     * @param activePersonaId the ID of the current persona
     * @param packet          the {@link TA} instance
     * @param eventSessionEntity the {@link EventSessionEntity} instance
     */
    protected final void prepareBasicEventData(EventDataEntity eventDataEntity, Long activePersonaId, TA packet, EventSessionEntity eventSessionEntity) {
        ClientPhysicsMetrics clientPhysicsMetrics = packet.getPhysicsMetrics();

        eventDataEntity.setAlternateEventDurationInMilliseconds(packet.getAlternateEventDurationInMilliseconds());
        eventDataEntity.setCarId(packet.getCarId());
        eventDataEntity.setEventDurationInMilliseconds(packet.getEventDurationInMilliseconds());
        eventDataEntity.setFinishReason(packet.getFinishReason());
        eventDataEntity.setHacksDetected(packet.getHacksDetected());

        // Sauvegarder temporairement rank = 0, le vrai calcul se fera après que tous les joueurs aient fini
        // Car calculer maintenant donnerait des rangs incorrects (ex: plusieurs joueurs rank 1)
        eventDataEntity.setRank(packet.getRank());
        
        eventDataEntity.setPersonaId(activePersonaId);
        eventDataEntity.setEventModeId(eventDataEntity.getEvent().getEventModeId());
        eventDataEntity.setServerTimeEnded(System.currentTimeMillis());
        eventDataEntity.setServerTimeInMilliseconds(eventDataEntity.getServerTimeEnded() - eventDataEntity.getServerTimeStarted());

        if (clientPhysicsMetrics != null) {
            eventDataEntity.setAccelerationAverage(clientPhysicsMetrics.getAccelerationAverage());
            eventDataEntity.setAccelerationMaximum(clientPhysicsMetrics.getAccelerationMaximum());
            eventDataEntity.setAccelerationMedian(clientPhysicsMetrics.getAccelerationMedian());
            eventDataEntity.setSpeedAverage(clientPhysicsMetrics.getSpeedAverage());
            eventDataEntity.setSpeedMaximum(clientPhysicsMetrics.getSpeedMaximum());
            eventDataEntity.setSpeedMedian(clientPhysicsMetrics.getSpeedMedian());
        }

        //EVENT_DATA_SETUPS
        CarEntity carInfo = carDAO.find(packet.getCarId());
        String carHash = HelpingTools.calcHash(JAXBUtility.marshal(OwnedCarConverter.makeCarSetupTrans(carInfo)));
        EventDataSetupEntity carSetup = eventDataSetupDAO.findByHash(carHash);
        if(carSetup == null) {
            EventDataSetupEntity carSetupTmp = new EventDataSetupEntity();
            carSetupTmp.setCarId(packet.getCarId());
            carSetupTmp.setHash(carHash);
            carSetupTmp.setPersonaId(activePersonaId);
            carSetupTmp.setCarName(carInfo.getName());
            carSetupTmp.setCarClassHash(carInfo.getCarClassHash());
            carSetupTmp.setCarRating(carInfo.getRating());
            carSetupTmp.setPerformanceParts(OwnedCarConverter.getPerformanceParts(carInfo));
            carSetupTmp.setSkillmodParts(OwnedCarConverter.getSkillModParts(carInfo));
            carSetupTmp.setVisualParts(OwnedCarConverter.getVisualParts(carInfo));
            eventDataSetupDAO.insert(carSetupTmp);
        }
        
        eventDataEntity.setEventDataSetupHash(carHash);
    }

    /**
     * Sets up Race Again and updates the info on the given {@link TR} instance
     *
     * @param eventSessionEntity the {@link EventSessionEntity} instance
     * @param result             the {@link TR} instance
     * @param packet             the {@link TA} arbitration packet (to check finishReason)
     */
    protected final void prepareRaceAgain(EventSessionEntity eventSessionEntity, TR result, TA packet) {
        ExitPath exitPath = ExitPath.EXIT_TO_FREEROAM;
        EventEntity eventEntity = eventSessionEntity.getEvent();

        Long eventSessionId = eventSessionEntity.getId();
        logger.info("@@@ Thread {} - prepareRaceAgain() called for EventSession {}, PersonaId {}, finishReason={}, lobby={}, raceAgainEnabled={}", 
            Thread.currentThread().getName(), 
            eventSessionId, 
            eventSessionEntity.getLobby() != null ? eventSessionEntity.getLobby().getPersonaId() : "null",
            packet.getFinishReason(),
            eventSessionEntity.getLobby() != null ? eventSessionEntity.getLobby().getId() : "null",
            eventEntity.isRaceAgainEnabled());

        // Only set up Race Again if:
        // 1. It's enabled for the event
        // 2. The session was multiplayer
        // REMOVED: 3. The player FINISHED the race (finishReason = 22) - TESTING WITHOUT THIS CHECK
        if (eventSessionEntity.getLobby() != null 
            && eventEntity.isRaceAgainEnabled()) {
            
            logger.info("Thread {} - Race Again enabled for EventSession {}", 
                Thread.currentThread().getName(), eventSessionId);
            
            LobbyEntity oldLobby = eventSessionEntity.getLobby();
            
            // Get the first entrant who joined the lobby (creator or first joiner)
            LobbyEntrantEntity firstEntrant = null;
            if (oldLobby.getEntrants() != null && !oldLobby.getEntrants().isEmpty()) {
                firstEntrant = oldLobby.getEntrants().get(0);
            }
            
            // Determine the car class hash and persona level for event selection
            int carClassHash = oldLobby.getEvent().getCarClassHash(); // Fallback to event's class
            int personaLevel = 10; // Default level
            Long personaId = oldLobby.getPersonaId();
            
            if (firstEntrant != null) {
                PersonaEntity firstPersona = personaDAO.find(firstEntrant.getPersona().getPersonaId());
                if (firstPersona != null) {
                    personaLevel = firstPersona.getLevel();
                    personaId = firstPersona.getPersonaId();
                    CarEntity firstCar = personaBO.getDefaultCarEntity(firstPersona.getPersonaId());
                    if (firstCar != null) {
                        carClassHash = firstCar.getCarClassHash();
                    }
                }
            }
            
            int previousEventModeId = oldLobby.getEvent().getEventModeId();
            
            logger.info("RACE_AGAIN: Delegating to RaceAgainBO for EventSession {} (mode {}, class {}, level {})", 
                eventSessionId, previousEventModeId, carClassHash, personaLevel);
            
            // Use RaceAgainBO to get or create lobby + select event atomically
            // This ensures only ONE lobby is created per event session AND all players get same event
            RaceAgainBO.RaceAgainResult raceAgainResult = raceAgainBO.getOrCreateRaceAgainLobby(
                eventSessionEntity,
                personaId,
                carClassHash,
                previousEventModeId,
                personaLevel
            );
            
            if (raceAgainResult == null || raceAgainResult.getLobby() == null || raceAgainResult.getEvent() == null) {
                logger.error("RACE_AGAIN: Failed to get or create nextLobby for EventSession {}", eventSessionId);
                result.setExitPath(ExitPath.EXIT_TO_FREEROAM);
                return;
            }
            
            LobbyEntity nextLobbyEntity = raceAgainResult.getLobby();
            EventEntity selectedEvent = raceAgainResult.getEvent();

            int inviteLifetime = 0;

            if(parameterBo.getIntParam("SBRWR_MINIMUMLOBBYTIME") == 0) {
                inviteLifetime = nextLobbyEntity.getLobbyCountdownInMilliseconds(eventEntity.getLobbyCountdownTime());
            } else {
                inviteLifetime = parameterBo.getIntParam("SBRWR_MINIMUMLOBBYTIME");
            }
            
            // lobby must have more than 6 seconds left
            if (inviteLifetime > 6000) {
                result.setLobbyInviteId(nextLobbyEntity.getId());
                result.setInviteLifetimeInMilliseconds(inviteLifetime);
                // Set the new event ID so the client displays the correct event info
                result.setEventId(selectedEvent.getId());
                exitPath = ExitPath.EXIT_TO_LOBBY;
                
                logger.info("RACE_AGAIN: Sending result with EventId={} for lobby {}", 
                    selectedEvent.getId(), nextLobbyEntity.getId());
            }
        }

        result.setExitPath(exitPath);
    }

    /**
     * Trigger an EVENT achievement progress update
     *
     * @param eventDataEntity    the {@link EventDataEntity} instance
     * @param eventSessionEntity the {@link EventSessionEntity} instance
     * @param activePersonaId    the active persona ID
     * @param packet             the {@link TA} instance
     * @param transaction        the {@link AchievementTransaction} instance
     */
    protected void updateEventAchievements(EventDataEntity eventDataEntity, EventSessionEntity eventSessionEntity, Long activePersonaId, TA packet, AchievementTransaction transaction) {
        PersonaEntity personaEntity = personaDAO.find(activePersonaId);
        EventEntity eventEntity = eventDataEntity.getEvent();

        transaction.add("EVENT", Map.of(
                "persona", personaEntity,
                "event", eventEntity,
                "eventData", eventDataEntity,
                "eventSession", eventSessionEntity,
                "eventContext", new AchievementEventContext(EventMode.fromId(eventEntity.getEventModeId()), packet, eventSessionEntity),
                "car", personaBO.getDefaultCarEntity(activePersonaId)
        ));
    }

    /**
     * Commite les achievements d'événement APRÈS que les rangs finaux aient été recalculés
     * Ceci garantit que les achievements utilisent le bon rang final au lieu du rang temporaire
     * 
     * @param eventDataEntity Les données d'événement mises à jour avec le rang final
     * @param activePersonaId L'ID du persona 
     * @param transaction La transaction d'achievement à committer
     */
    protected void commitEventAchievementsWithFinalRank(EventDataEntity eventDataEntity, Long activePersonaId, AchievementTransaction transaction) {
        PersonaEntity personaEntity = personaDAO.find(activePersonaId);
        
        // À ce stade, eventDataEntity.getRank() contient le rang final recalculé
        // Les achievements peuvent maintenant utiliser les données correctes
        
        achievementBO.commitTransaction(personaEntity, transaction);
    }

    /**
     * Calcule le rang du joueur en fonction du temps de course par rapport aux autres participants
     * @param eventSessionEntity La session de course
     * @param personaId L'ID du persona du joueur
     * @param raceTime Le temps de course du joueur en millisecondes
     * @return Le rang calculé (1 = premier, 2 = deuxième, etc.)
     */
    protected int calculateRankBasedOnTime(EventSessionEntity eventSessionEntity, Long personaId, long raceTime) {
        // Récupère tous les entrants de la session
        java.util.List<EventDataEntity> entrants = eventDataDAO.getRacers(eventSessionEntity.getId());
        
        // Compte combien de joueurs ont un temps meilleur (plus petit)
        int rank = 1;
        for (EventDataEntity entrant : entrants) {
            // Ne pas se comparer à soi-même
            if (entrant.getPersonaId().equals(personaId)) {
                continue;
            }
            
            // Si l'autre joueur a terminé la course (finishReason = 22) et a un temps meilleur
            if (entrant.getFinishReason() == 22 && entrant.getEventDurationInMilliseconds() < raceTime) {
                rank++;
            }
        }
        
        return rank;
    }

    /**
     * Calcule un rang temporaire pour l'affichage XMPP (pas définitif)
     * @param eventSessionEntity La session de course
     * @param personaId L'ID du persona du joueur
     * @param raceTime Le temps de course du joueur en millisecondes
     * @return Le rang temporaire (sera recalculé plus tard)
     */
    protected int calculateTemporaryRank(EventSessionEntity eventSessionEntity, Long personaId, long raceTime) {
        // Pour l'instant, utilise l'ancienne logique pour l'affichage temporaire
        // Le vrai calcul se fera dans recalculateAllRanks()
        java.util.List<EventDataEntity> entrants = eventDataDAO.getRacers(eventSessionEntity.getId());
        
        int rank = 1;
        for (EventDataEntity entrant : entrants) {
            if (entrant.getPersonaId().equals(personaId)) {
                continue;
            }
            
            if (entrant.getFinishReason() == 22 && entrant.getEventDurationInMilliseconds() < raceTime) {
                rank++;
            }
        }
        
        return rank;
    }

    /**
     * Recalcule tous les rangs d'une session après que tous les joueurs aient terminé
     * Cela évite le problème où plusieurs joueurs se calculent comme étant 1er
     * @param eventSessionEntity La session de course
     */
    protected void recalculateAllRanks(EventSessionEntity eventSessionEntity) {
        // Récupère tous les participants qui ont terminé normalement
        java.util.List<EventDataEntity> finishedRacers = eventDataDAO.getRacers(eventSessionEntity.getId())
            .stream()
            .filter(racer -> racer.getFinishReason() == 22)
            .sorted((a, b) -> Long.compare(a.getEventDurationInMilliseconds(), b.getEventDurationInMilliseconds()))
            .collect(java.util.stream.Collectors.toList());
        
        // Assigne les rangs dans l'ordre croissant des temps
        for (int i = 0; i < finishedRacers.size(); i++) {
            EventDataEntity racer = finishedRacers.get(i);
            racer.setRank(i + 1); // Rang 1, 2, 3, etc.
            eventDataDAO.update(racer);
        }
    }
}
