package com.soapboxrace.core.bo;

import com.soapboxrace.core.bo.util.AchievementEventContext;
import com.soapboxrace.core.dao.*;
import com.soapboxrace.core.jpa.*;
import com.soapboxrace.core.xmpp.OpenFireRestApiCli;
import com.soapboxrace.core.xmpp.OpenFireSoapBoxCli;
import com.soapboxrace.core.xmpp.XmppEvent;
import com.soapboxrace.jaxb.http.ArbitrationPacket;
import com.soapboxrace.jaxb.http.ClientPhysicsMetrics;
import com.soapboxrace.jaxb.http.EventResult;
import com.soapboxrace.jaxb.http.ExitPath;
import com.soapboxrace.core.bo.util.OwnedCarConverter;
import com.soapboxrace.jaxb.util.JAXBUtility;
import com.soapboxrace.core.bo.util.HelpingTools;

import javax.inject.Inject;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class EventResultBO<TA extends ArbitrationPacket, TR extends EventResult> {

    private static final Logger logger = LoggerFactory.getLogger(EventResultBO.class);
    
    private static final java.util.concurrent.ConcurrentHashMap<String, RaceAgainLobbyInfo> raceAgainLobbies 
        = new java.util.concurrent.ConcurrentHashMap<>();
    
    private static class RaceAgainLobbyInfo {
        final LobbyEntity lobby;
        final EventEntity event;
        final long createdAt;
        
        RaceAgainLobbyInfo(LobbyEntity lobby, EventEntity event) {
            this.lobby = lobby;
            this.event = event;
            this.createdAt = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            long age = System.currentTimeMillis() - createdAt;
            return age > 300000;
        }
    }

    @Inject
    protected MatchmakingBO matchmakingBO;

    @Inject
    protected LobbyBO lobbyBO;

    @Inject
    protected ParameterBO parameterBo;

    @Inject
    protected PersonaBO personaBO;

    @Inject
    protected PersonaDAO personaDAO;

    @Inject
    private CarDAO carDAO;

    @Inject
    private EventDataSetupDAO eventDataSetupDAO;

    @Inject
    private EventDataDAO eventDataDAO;

    @Inject
    protected AchievementBO achievementBO;

    @Inject
    protected EventDAO eventDAO;

    @Inject
    protected LobbyDAO lobbyDAO;

    @Inject
    protected EventSessionDAO eventSessionDAO;
    
    @Inject
    protected TokenSessionBO tokenSessionBO;
    
    @Inject
    protected OpenFireSoapBoxCli openFireSoapBoxCli;
    
    @Inject
    protected OpenFireRestApiCli openFireRestApiCli;

    @Inject
    protected AsyncXmppBO asyncXmppBO;
    
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

    protected abstract TR handleInternal(EventSessionEntity eventSessionEntity, Long activePersonaId, TA packet);

    protected void prepareBasicEventData(EventDataEntity eventDataEntity, Long activePersonaId, TA packet, EventSessionEntity eventSessionEntity) {
        ClientPhysicsMetrics clientPhysicsMetrics = packet.getPhysicsMetrics();

        eventDataEntity.setAlternateEventDurationInMilliseconds(packet.getAlternateEventDurationInMilliseconds());
        eventDataEntity.setCarId(packet.getCarId());
        eventDataEntity.setEventDurationInMilliseconds(packet.getEventDurationInMilliseconds());
        eventDataEntity.setFinishReason(packet.getFinishReason());
        eventDataEntity.setHacksDetected(packet.getHacksDetected());

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

    protected void prepareRaceAgain(EventSessionEntity eventSessionEntity, Long activePersonaId, TR result, TA packet) {
        ExitPath exitPath = ExitPath.EXIT_TO_FREEROAM;
        EventEntity eventEntity = eventSessionEntity.getEvent();

        matchmakingBO.removePlayerFromRaceNowQueue(activePersonaId);
        logger.info("RACE_AGAIN: Removed persona {} from RaceNow queue after race completion", activePersonaId);
        
        if (Math.random() < 0.1) {
            raceAgainLobbies.entrySet().removeIf(entry -> entry.getValue().isExpired());
        }

        if (eventSessionEntity.getLobby() != null 
            && eventEntity.isRaceAgainEnabled()
            && packet.getFinishReason() == 22) {
            
            LobbyEntity oldLobby = eventSessionEntity.getLobby();
            
            if (oldLobby.getIsPrivate() != null && oldLobby.getIsPrivate()) {
                logger.info("RACE_AGAIN: Skipping - lobby {} was PRIVATE (no Race Again for private lobbies)", oldLobby.getId());
                result.setExitPath(ExitPath.EXIT_TO_FREEROAM);
                return;
            }
            
            try {
                lobbyBO.removeEntrantFromLobby(activePersonaId, oldLobby.getId());
                logger.info("RACE_AGAIN: Removed persona {} from old lobby {}", activePersonaId, oldLobby.getId());
            } catch (Exception e) {
                logger.debug("RACE_AGAIN: Persona {} not in old lobby {} ({})", 
                    activePersonaId, oldLobby.getId(), e.getMessage());
            }
            
            PersonaEntity activePersona = personaDAO.find(activePersonaId);
            if (activePersona == null) {
                logger.warn("RACE_AGAIN: Active persona {} not found", activePersonaId);
                result.setExitPath(ExitPath.EXIT_TO_FREEROAM);
                return;
            }
            
            UserEntity activeUser = activePersona.getUser();
            if (activeUser == null) {
                logger.warn("RACE_AGAIN: User for persona {} not found", activePersonaId);
                result.setExitPath(ExitPath.EXIT_TO_FREEROAM);
                return;
            }
            
            if (!activeUser.isRaceAgainEnabled()) {
                logger.info("RACE_AGAIN: User {} (persona {}) has Race Again DISABLED", 
                    activeUser.getId(), activePersonaId);
                result.setExitPath(ExitPath.EXIT_TO_FREEROAM);
                return;
            }
            
            int userRaceAgainMode = activeUser.getRaceAgainMode();
            String modeKey = (userRaceAgainMode == 1) ? "REPEAT" : "RANDOM";
            String lobbyKey = eventSessionEntity.getId() + "_" + modeKey;
            
            int personaLevel = activePersona.getLevel();
            CarEntity playerCar = personaBO.getDefaultCarEntity(activePersonaId);
            int carClassHash = (playerCar != null) ? playerCar.getCarClassHash() : oldLobby.getEvent().getCarClassHash();
            
            logger.info("RACE_AGAIN: Processing persona {} with mode {} (eventSessionId: {}, lobbyKey: {})", 
                activePersonaId, modeKey, eventSessionEntity.getId(), lobbyKey);
            
            LobbyEntity nextLobbyEntity = null;
            EventEntity selectedEvent = null;
            
            synchronized (lobbyKey.intern()) {
                RaceAgainLobbyInfo lobbyInfo = raceAgainLobbies.get(lobbyKey);
                
                logger.info("RACE_AGAIN: Checking map for '{}': {}", 
                    lobbyKey, lobbyInfo != null ? "found lobby " + lobbyInfo.lobby.getId() : "not found");
                
                if (lobbyInfo != null && !lobbyInfo.isExpired()) {
                    nextLobbyEntity = lobbyInfo.lobby;
                    selectedEvent = lobbyInfo.event;
                    
                    int currentEntrants = nextLobbyEntity.getEntrants() != null ? nextLobbyEntity.getEntrants().size() : 0;
                    int maxPlayers = selectedEvent.getMaxPlayers();
                    
                    if (currentEntrants >= maxPlayers) {
                        logger.info("RACE_AGAIN: {} lobby {} is full ({}/{}), creating new",
                            modeKey, nextLobbyEntity.getId(), currentEntrants, maxPlayers);
                        nextLobbyEntity = null;
                        selectedEvent = null;
                        raceAgainLobbies.remove(lobbyKey);
                    } else {
                        logger.info("RACE_AGAIN: {} - Reusing lobby {} for persona {} ({}/{} players)",
                            modeKey, nextLobbyEntity.getId(), activePersonaId, currentEntrants, maxPlayers);
                    }
                }
                
                if (nextLobbyEntity == null) {
                    if (userRaceAgainMode == 1) {
                        selectedEvent = oldLobby.getEvent();
                        
                        if (selectedEvent == null || !selectedEvent.getIsEnabled() || !selectedEvent.isRaceAgainEnabled()) {
                            logger.warn("RACE_AGAIN: REPEAT event {} not available", 
                                selectedEvent != null ? selectedEvent.getId() : "null");
                            result.setExitPath(ExitPath.EXIT_TO_FREEROAM);
                            return;
                        }
                        
                        logger.info("RACE_AGAIN: REPEAT - reusing event {}", selectedEvent.getId());
                    } else {
                        int eventModeId = oldLobby.getEvent().getEventModeId();
                        int previousEventId = oldLobby.getEvent().getId();
                        
                        List<EventEntity> eligibleEvents = eventDAO.findEligibleForRaceAgainByMode(
                            personaLevel, carClassHash, eventModeId, previousEventId
                        );
                        
                        if (eligibleEvents == null || eligibleEvents.isEmpty()) {
                            logger.warn("RACE_AGAIN: No eligible events for RANDOM mode");
                            result.setExitPath(ExitPath.EXIT_TO_FREEROAM);
                            return;
                        }
                        
                        selectedEvent = eligibleEvents.get(new java.util.Random().nextInt(eligibleEvents.size()));
                        logger.info("RACE_AGAIN: RANDOM - selected event {} from {} eligible",
                            selectedEvent.getId(), eligibleEvents.size());
                    }
                    
                    int eventCarClassHash = selectedEvent.getCarClassHash();
                    if (eventCarClassHash != 607077938 && eventCarClassHash != carClassHash) {
                        logger.error("RACE_AGAIN: {} event {} incompatible car class", modeKey, selectedEvent.getId());
                        result.setExitPath(ExitPath.EXIT_TO_FREEROAM);
                        return;
                    }
                    
                    nextLobbyEntity = lobbyBO.createLobby(
                        activePersonaId,
                        selectedEvent.getId(),
                        carClassHash,
                        false
                    );
                    
                    if (nextLobbyEntity == null || nextLobbyEntity.getId() == null) {
                        logger.error("RACE_AGAIN: Failed to create {} lobby", modeKey);
                        result.setExitPath(ExitPath.EXIT_TO_FREEROAM);
                        return;
                    }
                    
                    RaceAgainLobbyInfo newInfo = new RaceAgainLobbyInfo(nextLobbyEntity, selectedEvent);
                    raceAgainLobbies.put(lobbyKey, newInfo);
                    
                    logger.info("RACE_AGAIN: {} - Created lobby {} for event {} (persona {}), stored in map",
                        modeKey, nextLobbyEntity.getId(), selectedEvent.getId(), activePersonaId);
                }
            }
            
            if (nextLobbyEntity == null || selectedEvent == null) {
                logger.error("RACE_AGAIN: Failed to get lobby/event for persona {}", activePersonaId);
                result.setExitPath(ExitPath.EXIT_TO_FREEROAM);
                return;
            }
            
            int inviteLifetime = nextLobbyEntity.getLobbyCountdownInMilliseconds(selectedEvent.getLobbyCountdownTime());
            int minimumRaceAgainTime = parameterBo.getIntParam("SBRWR_RACE_AGAIN_MIN_TIME", 20000);
            
            if (inviteLifetime > minimumRaceAgainTime) {
                // NE PAS envoyer EventTimedOut ici !
                // L'EventTimedOut dit au client de quitter l'événement et de se déconnecter du
                // UDP relay IMMÉDIATEMENT, même si d'autres joueurs sont encore en course.
                // Cela coupait la synchro avec le race server pour le joueur qui finit en premier.
                // Le EXIT_TO_LOBBY dans la réponse HTTP suffit pour que le client sache qu'il doit
                // transitionner vers le nouveau lobby. Le nettoyage XMPP se fera dans acceptInvite().
                
                result.setLobbyInviteId(nextLobbyEntity.getId());
                result.setInviteLifetimeInMilliseconds(inviteLifetime);
                result.setEventId(selectedEvent.getId());
                exitPath = ExitPath.EXIT_TO_LOBBY;
                
                logger.info("RACE_AGAIN: Sending persona {} to {} lobby {} (event {}, {}ms remaining)", 
                    activePersonaId, modeKey, nextLobbyEntity.getId(), selectedEvent.getId(), inviteLifetime);
            } else {
                logger.info("RACE_AGAIN: Insufficient time ({}ms < {}ms) for persona {}", 
                    inviteLifetime, minimumRaceAgainTime, activePersonaId);
            }
        }

        result.setExitPath(exitPath);
    }
    
    public static void cleanupRaceAgainLobby(Long lobbyId) {
        if (lobbyId == null) return;
        
        raceAgainLobbies.entrySet().removeIf(entry -> {
            if (entry.getValue().lobby.getId().equals(lobbyId)) {
                logger.info("RACE_AGAIN: Cleaning up lobby {} from map", lobbyId);
                return true;
            }
            return false;
        });
    }
    
    public static void cleanupExpiredRaceAgainLobbies() {
        int removedCount = 0;
        
        for (java.util.Iterator<java.util.Map.Entry<String, RaceAgainLobbyInfo>> it = raceAgainLobbies.entrySet().iterator(); 
             it.hasNext(); ) {
            java.util.Map.Entry<String, RaceAgainLobbyInfo> entry = it.next();
            RaceAgainLobbyInfo info = entry.getValue();
            
            if (info.isExpired()) {
                it.remove();
                removedCount++;
                logger.info("RACE_AGAIN: Auto-cleanup removed lobby {} from map (expired)", info.lobby.getId());
            }
        }
        
        if (removedCount > 0) {
            logger.info("RACE_AGAIN: Auto-cleanup removed {} expired lobby(ies) from map", removedCount);
        }
    }
    
    public static boolean isRaceAgainLobby(Long lobbyId) {
        if (lobbyId == null) return false;
        
        return raceAgainLobbies.values().stream()
            .anyMatch(info -> info.lobby.getId().equals(lobbyId));
    }

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

    protected void commitEventAchievementsWithFinalRank(EventDataEntity eventDataEntity, Long activePersonaId, AchievementTransaction transaction) {
        PersonaEntity personaEntity = personaDAO.find(activePersonaId);
        try {
            achievementBO.commitTransaction(personaEntity, transaction);
        } catch (Exception e) {
            logger.warn("Achievement commit failed for PersonaId={} (likely deadlock, result preserved): {}",
                activePersonaId, e.getMessage());
        }
    }

    protected int calculateRankBasedOnTime(EventSessionEntity eventSessionEntity, Long personaId, long raceTime) {
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

}


