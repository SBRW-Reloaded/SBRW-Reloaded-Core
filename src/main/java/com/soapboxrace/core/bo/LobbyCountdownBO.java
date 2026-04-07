package com.soapboxrace.core.bo;

import com.soapboxrace.core.dao.EventSessionDAO;
import com.soapboxrace.core.dao.LobbyDAO;
import com.soapboxrace.core.dao.LobbyEntrantDAO;
import com.soapboxrace.core.jpa.EventSessionEntity;
import com.soapboxrace.core.jpa.LobbyEntity;
import com.soapboxrace.core.jpa.LobbyEntrantEntity;
import com.soapboxrace.core.jpa.TokenSessionEntity;
import com.soapboxrace.core.xmpp.OpenFireSoapBoxCli;
import com.soapboxrace.jaxb.http.Entrants;
import com.soapboxrace.jaxb.http.LobbyCountdown;
import com.soapboxrace.jaxb.http.LobbyEntrantInfo;
import com.soapboxrace.jaxb.http.LobbyEntrantState;
import com.soapboxrace.jaxb.xmpp.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import javax.ejb.*;
import javax.inject.Inject;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
@Lock(LockType.READ)
public class LobbyCountdownBO {
    private static final Logger logger = LoggerFactory.getLogger(LobbyCountdownBO.class);

    // Timers actifs par lobbyId — permet d'annuler un timer si le nombre de joueurs redescend à 1
    private final ConcurrentHashMap<Long, Timer> activeTimers = new ConcurrentHashMap<>();
    
    // Timers de suppression différée pour les lobbies Race Again vides
    // Utilisé pour donner un délai de grâce avant de supprimer un lobby Race Again qui devient vide
    private final ConcurrentHashMap<Long, Timer> emptyLobbyDeletionTimers = new ConcurrentHashMap<>();

    @Resource
    private TimerService timerService;

    @Inject
    private LobbyDAO lobbyDAO;

    @Inject
    private LobbyEntrantDAO lobbyEntrantDAO;

    @Inject
    private EventSessionDAO eventSessionDAO;

    @Inject
    private TokenSessionBO tokenSessionBO;

    @Inject
    private ParameterBO parameterBO;

    @Inject
    private LobbyMessagingBO lobbyMessagingBO;

    @Inject
    private OpenFireSoapBoxCli openFireSoapBoxCli;

    /**
     * Repasse le lobby en mode "en attente" lorsqu'il ne reste qu'un seul joueur :
     * annule le timer serveur, rafraîchit startedTime (visibilité dans les recherches),
     * et envoie IsWaiting=true au joueur restant pour stopper son décompte côté client.
     */
    public void resetSoloLobby(LobbyEntity lobbyEntity) {
        Long lobbyId = lobbyEntity.getId();
        int fullTime = lobbyEntity.getEvent().getLobbyCountdownTime();

        // Annuler le timer serveur
        cancelLobbyTimer(lobbyId);

        // Rafraîchir startedTime pour rester visible dans les recherches de lobby
        lobbyEntity.setStartedTime(LocalDateTime.now());
        lobbyDAO.update(lobbyEntity);

        // CRITICAL FIX : Refetch le lobby pour obtenir la liste d'entrants à jour depuis la DB
        // Sans cela, on pourrait envoyer le message XMPP au mauvais joueur si la collection
        // Java est "stale" (pas synchronisée avec les suppressions récentes)
        lobbyEntity = lobbyDAO.find(lobbyId);
        
        // Vérifier qu'il reste bien un joueur (sécurité)
        if (lobbyEntity == null || lobbyEntity.getEntrants().isEmpty()) {
            logger.warn("RESET_SOLO_LOBBY: Lobby {} no longer exists or is empty, aborting reset", lobbyId);
            return;
        }
        
        logger.info("RESET_SOLO_LOBBY: Lobby {} has {} entrant(s) after refetch", 
            lobbyId, lobbyEntity.getEntrants().size());
        for (LobbyEntrantEntity entrant : lobbyEntity.getEntrants()) {
            logger.info("RESET_SOLO_LOBBY: Lobby {} - Entrant PersonaId={}", 
                lobbyId, entrant.getPersona().getPersonaId());
        }

        // Envoyer IsWaiting=true au(x) joueur(s) restant(s) pour stopper le décompte côté client
        LobbyCountdown waitingCountdown = new LobbyCountdown();
        waitingCountdown.setLobbyId(lobbyId);
        waitingCountdown.setEventId(lobbyEntity.getEvent().getId());
        waitingCountdown.setLobbyStuckDurationInMilliseconds(10000);
        waitingCountdown.setLobbyCountdownInMilliseconds(fullTime);
        waitingCountdown.setIsWaiting(true);
        XMPP_ResponseTypeLobbyCountdown response = new XMPP_ResponseTypeLobbyCountdown();
        response.setLobbyCountdown(waitingCountdown);
        for (LobbyEntrantEntity entrant : lobbyEntity.getEntrants()) {
            openFireSoapBoxCli.send(response, entrant.getPersona().getPersonaId());
            logger.info("RESET_SOLO_LOBBY: Sent waiting state to PersonaId={}", 
                entrant.getPersona().getPersonaId());
        }
        logger.info("Lobby {} back to waiting state for solo player", lobbyId);
    }

    public boolean hasActiveTimer(Long lobbyId) {
        return activeTimers.containsKey(lobbyId);
    }
    
    /**
     * Récupère le temps de countdown à utiliser pour un lobby.
     * Priorité : paramètre BDD LOBBY_COUNTDOWN_TIME > event.getLobbyCountdownTime()
     * Cela permet un contrôle global via la BDD tout en gardant la flexibilité par événement.
     * 
     * @param lobbyEntity Le lobby dont on veut le countdown
     * @return Le temps de countdown en millisecondes
     */
    public int getCountdownTimeForLobby(LobbyEntity lobbyEntity) {
        // Vérifier s'il existe un override global dans les paramètres
        // Si le paramètre n'existe pas ou vaut 0, utiliser la valeur de l'événement
        int globalCountdown = parameterBO.getIntParam("LOBBY_COUNTDOWN_TIME", 0);
        
        if (globalCountdown > 0) {
            // Paramètre BDD défini : utiliser cette valeur pour tous les lobbies
            return globalCountdown;
        } else {
            // Pas de paramètre global : utiliser la valeur spécifique de l'événement
            return lobbyEntity.getEvent().getLobbyCountdownTime();
        }
    }
    
    public void scheduleLobbyStart(LobbyEntity lobbyEntity) {
        Timer existing = activeTimers.get(lobbyEntity.getId());
        if (existing != null) {
            logger.info("Schedule request ignored for lobby {}: timer already active", lobbyEntity.getId());
            return;
        }

        cancelLobbyTimer(lobbyEntity.getId());
        TimerConfig timerConfig = new TimerConfig();
        timerConfig.setInfo(lobbyEntity.getId());
        int countdownTime = getCountdownTimeForLobby(lobbyEntity);
        Timer timer = timerService.createSingleActionTimer(countdownTime, timerConfig);
        activeTimers.put(lobbyEntity.getId(), timer);
        long expectedLaunchAt = System.currentTimeMillis() + countdownTime;
        logger.info("Scheduled lobby start for lobby {} in {} ms (ExpectedLaunchAt={})", lobbyEntity.getId(), countdownTime, expectedLaunchAt);
    }

    public void scheduleLobbyStart(LobbyEntity lobbyEntity, Integer countdownTime) {
        cancelLobbyTimer(lobbyEntity.getId());
        TimerConfig timerConfig = new TimerConfig();
        timerConfig.setInfo(lobbyEntity.getId());
        Timer timer = timerService.createSingleActionTimer(countdownTime, timerConfig);
        activeTimers.put(lobbyEntity.getId(), timer);
        long expectedLaunchAt = System.currentTimeMillis() + countdownTime;
        logger.info("Scheduled lobby start (full) for lobby {} in {} ms (ExpectedLaunchAt={})", lobbyEntity.getId(), countdownTime, expectedLaunchAt);
    }

    /**
     * Annule le timer d'un lobby (ex: quand le nombre de joueurs redescend à 1).
     * Sans effet si aucun timer n'est actif pour ce lobby.
     * @Lock(WRITE) sérialise cette méthode avec onTimeout pour éviter la race condition.
     */
    @Lock(LockType.WRITE)
    public void cancelLobbyTimer(Long lobbyId) {
        Timer existing = activeTimers.remove(lobbyId);
        if (existing != null) {
            try {
                existing.cancel();
                logger.info("Cancelled countdown timer for lobby {}", lobbyId);
            } catch (Exception e) {
                logger.debug("Could not cancel timer for lobby {} (may have already fired): {}", lobbyId, e.getMessage());
            }
        }
    }

    public void markLobbyHadPlayers(Long lobbyId) {
        LobbyEntity lobbyEntity = lobbyDAO.find(lobbyId);
        if (lobbyEntity != null) {
            lobbyEntity.setHasHadPlayers(true);
            lobbyDAO.update(lobbyEntity);
        }
    }

    /**
     * Programme la suppression différée d'un lobby Race Again vide.
     * Utilisé pour donner un délai de grâce avant de supprimer un lobby Race Again qui devient vide,
     * permettant aux autres joueurs en cours de finir leur course de rejoindre le lobby.
     * 
     * @param lobbyId L'ID du lobby à supprimer après le délai
     * @param delayInMs Le délai en millisecondes avant suppression (typiquement 30000 = 30 secondes)
     */
    public void scheduleRaceAgainLobbyDeletion(Long lobbyId, int delayInMs) {
        // Annuler un éventuel timer de suppression existant pour ce lobby
        Timer existing = emptyLobbyDeletionTimers.remove(lobbyId);
        if (existing != null) {
            try {
                existing.cancel();
                logger.debug("RACE_AGAIN: Cancelled previous deletion timer for lobby {}", lobbyId);
            } catch (Exception e) {
                logger.debug("Could not cancel previous deletion timer for lobby {}: {}", lobbyId, e.getMessage());
            }
        }
        
        // Créer un nouveau timer de suppression différée
        TimerConfig timerConfig = new TimerConfig();
        timerConfig.setInfo("DELETE_EMPTY_" + lobbyId);  // String pour distinguer du timer de démarrage
        Timer timer = timerService.createSingleActionTimer(delayInMs, timerConfig);
        emptyLobbyDeletionTimers.put(lobbyId, timer);
        
        logger.info("RACE_AGAIN: Scheduled deletion of empty lobby {} in {} ms", lobbyId, delayInMs);
    }

    /**
     * Annule la suppression différée d'un lobby Race Again.
     * Appelé quand un joueur rejoint un lobby qui était prévu pour suppression.
     * 
     * @param lobbyId L'ID du lobby dont on annule la suppression
     */
    public void cancelEmptyLobbyDeletion(Long lobbyId) {
        Timer timer = emptyLobbyDeletionTimers.remove(lobbyId);
        if (timer != null) {
            try {
                timer.cancel();
                logger.info("RACE_AGAIN: Cancelled scheduled deletion of lobby {} (player joined)", lobbyId);
            } catch (Exception e) {
                logger.debug("Could not cancel deletion timer for lobby {}: {}", lobbyId, e.getMessage());
            }
        }
    }

    /**
     * Gère la suppression effective d'un lobby Race Again vide après le délai de grâce.
     * Vérifie que le lobby est toujours vide avant de le supprimer (un joueur a pu rejoindre entre-temps).
     * 
     * @param lobbyId L'ID du lobby à supprimer
     */
    private void handleEmptyRaceAgainLobbyDeletion(Long lobbyId) {
        logger.info("RACE_AGAIN: Grace period expired for lobby {}, checking if still empty", lobbyId);
        
        LobbyEntity lobbyEntity = lobbyDAO.find(lobbyId);
        if (lobbyEntity == null) {
            logger.info("RACE_AGAIN: Lobby {} no longer exists (already deleted)", lobbyId);
            return;
        }
        
        // Vérifier que le lobby est toujours vide (un joueur a pu rejoindre pendant le délai de grâce)
        long currentCount = lobbyEntrantDAO.countByLobby(lobbyId);
        if (currentCount == 0) {
            logger.info("RACE_AGAIN: Lobby {} is still empty after grace period, deleting now", lobbyId);
            cancelLobbyTimer(lobbyId);  // Annuler tout timer de démarrage éventuel
            eventSessionDAO.nullifyLobbyReferences(lobbyId);
            lobbyDAO.markAsDeleted(lobbyEntity);
            EventResultBO.cleanupRaceAgainLobby(lobbyId);  // Retirer de la map Race Again
        } else {
            logger.info("RACE_AGAIN: Lobby {} is no longer empty ({} entrants), keeping it active", lobbyId, currentCount);
        }
    }

    @Schedule(minute = "*/5", hour = "*", persistent = false)
    @Lock(LockType.READ)
    public void cleanupEmptyLobbies() {
        // Nettoyer les lobbies vides de plus de 5 minutes
        LocalDateTime cutoffEmpty = LocalDateTime.now().minusMinutes(5);
        List<LobbyEntity> emptyLobbies = lobbyDAO.findAllEmptyOlderThan(cutoffEmpty);
        for (LobbyEntity lobby : emptyLobbies) {
            logger.info("Periodic cleanup: marking empty lobby {} as deleted (started: {})", 
                lobby.getId(), lobby.getStartedTime());
            // Annuler directement le timer sans passer par cancelLobbyTimer (@Lock WRITE) pour
            // éviter un upgrade READ→WRITE depuis la même transaction (cause du rollback cross-thread).
            Timer existing = activeTimers.remove(lobby.getId());
            if (existing != null) {
                try {
                    existing.cancel();
                } catch (Exception e) {
                    logger.debug("Could not cancel timer for lobby {} during cleanup: {}", lobby.getId(), e.getMessage());
                }
            }
            eventSessionDAO.nullifyLobbyReferences(lobby.getId());
            lobbyDAO.markAsDeleted(lobby);
        }
        
        // Nettoyer aussi les lobbies TRÈS anciens (30 minutes) même avec des entrants
        // Car ça indique probablement des joueurs déconnectés qui n'ont jamais quitté proprement
        LocalDateTime cutoffAbandoned = LocalDateTime.now().minusMinutes(30);
        List<LobbyEntity> abandonedLobbies = lobbyDAO.findAllOlderThan(cutoffAbandoned);
        for (LobbyEntity lobby : abandonedLobbies) {
            // Vérifier qu'il n'y a pas de course active (EventSession)
            // Si une course est en cours, ne pas supprimer le lobby
            if (lobby.getEvent() != null) {
                logger.info("Periodic cleanup: marking abandoned lobby {} with {} entrants as deleted (started: {})", 
                    lobby.getId(), lobby.getEntrants().size(), lobby.getStartedTime());
                
                Timer existing = activeTimers.remove(lobby.getId());
                if (existing != null) {
                    try {
                        existing.cancel();
                    } catch (Exception e) {
                        logger.debug("Could not cancel timer for lobby {} during cleanup: {}", lobby.getId(), e.getMessage());
                    }
                }
                eventSessionDAO.nullifyLobbyReferences(lobby.getId());
                lobbyDAO.markAsDeleted(lobby);
            }
        }
    }

    /**
     * Rafraîchit périodiquement le startedTime des lobbies en attente (1 joueur, pas de timer)
     * pour qu'ils restent trouvables via RaceNow même après plusieurs minutes d'attente.
     * Détecte aussi les lobbies où un joueur a quitté après que le countdown ait démarré.
     * Nettoie les lobbies jamais acceptés (0 entrants) après 2 minutes.
     * Actif uniquement si LOBBY_WAIT_FOR_MIN_PLAYERS est activé.
     */
    @Schedule(second = "0/10", minute = "*", hour = "*", persistent = false)
    @Lock(LockType.READ)
    public void refreshWaitingLobbies() {
        if (!parameterBO.getBoolParam("LOBBY_WAIT_FOR_MIN_PLAYERS")) {
            return; // Feature désactivée
        }

        try {
            logger.info("REFRESH_WAITING: Job started - searching for waiting lobbies");
            
            // Trouver tous les lobbies publics avec 0 ou 1 joueur
            List<LobbyEntity> waitingLobbies = lobbyDAO.findAllWithOnePlayer();
            
            logger.info("REFRESH_WAITING: Found {} lobbies with 0-1 player(s)", waitingLobbies.size());
            
            int refreshedCount = 0;
            int deletedCount = 0;
            
            for (LobbyEntity lobby : waitingLobbies) {
                Long lobbyId = lobby.getId();
                
                // CRITIQUE : Double-vérification du nombre réel d'entrants en base
                // Car le cache JPA peut être périmé (joueur qui a quitté/décliné)
                long actualEntrantCount = lobbyEntrantDAO.countByLobby(lobbyId);
                
                if (actualEntrantCount == 0) {
                    // Lobby jamais accepté : vérifier l'âge
                    LocalDateTime startedTime = lobby.getStartedTime();
                    LocalDateTime now = LocalDateTime.now();
                    long ageMinutes = java.time.Duration.between(startedTime, now).toMinutes();
                    
                    if (ageMinutes >= 2) {
                        // Lobby vide depuis plus de 2 minutes : personne ne l'a accepté, supprimer
                        logger.warn("REFRESH_WAITING: Lobby {} has 0 entrants and is {} minutes old - deleting", 
                            lobbyId, ageMinutes);
                        eventSessionDAO.nullifyLobbyReferences(lobbyId);
                        lobbyDAO.markAsDeleted(lobby);
                        EventResultBO.cleanupRaceAgainLobby(lobbyId);
                        deletedCount++;
                    } else {
                        // Lobby récent, laisser le temps au créateur d'accepter
                        logger.debug("REFRESH_WAITING: Lobby {} has 0 entrants but only {} minutes old - keeping", 
                            lobbyId, ageMinutes);
                    }
                    continue;
                }
                
                if (actualEntrantCount > 1) {
                    // Le lobby a déjà plusieurs joueurs : ne rien faire (le timer se lancera)
                    logger.info("REFRESH_WAITING: Lobby {} now has {} players - skipping refresh", lobbyId, actualEntrantCount);
                    continue;
                }
                
                // actualEntrantCount == 1 : traitement normal
                
                // Cas 1 : Lobby avec timer actif mais seulement 1 joueur
                // → Un joueur a quitté après que le countdown ait démarré
                // → Remettre en waiting state
                if (activeTimers.containsKey(lobbyId)) {
                    logger.info("REFRESH_WAITING: Lobby {} has active timer but only 1 player - resetting to waiting state", lobbyId);
                    resetSoloLobby(lobby);
                    refreshedCount++;
                }
                // Cas 2 : Lobby sans timer (vraiment en attente)
                // → Rafraîchir le startedTime pour rester dans la fenêtre de recherche
                else {
                    LocalDateTime oldStartedTime = lobby.getStartedTime();
                    LocalDateTime newStartedTime = LocalDateTime.now();
                    lobby.setStartedTime(newStartedTime);
                    lobbyDAO.update(lobby);
                    refreshedCount++;
                    
                    logger.info("REFRESH_WAITING: Refreshed startedTime for waiting lobby {} (old={}, new={})", 
                        lobbyId, oldStartedTime, newStartedTime);
                }
            }
            
            logger.info("REFRESH_WAITING: Job completed - Refreshed {} lobby(ies), deleted {} empty lobby(ies)", 
                refreshedCount, deletedCount);
        } catch (Exception e) {
            logger.error("REFRESH_WAITING: Error in job - {}", e.getMessage(), e);
        }
    }
    
    /**
     * Nettoie périodiquement les lobbies Race Again expirés.
     * S'exécute toutes les 30 secondes pour éviter l'accumulation de lobbies fantômes.
     */
    @Schedule(second = "0/30", minute = "*", hour = "*", persistent = false)
    @Lock(LockType.READ)
    public void cleanupRaceAgainLobbies() {
        try {
            logger.debug("RACE_AGAIN_CLEANUP: Starting periodic cleanup");
            EventResultBO.cleanupExpiredRaceAgainLobbies();
        } catch (Exception e) {
            logger.error("RACE_AGAIN_CLEANUP: Error during cleanup - {}", e.getMessage(), e);
        }
    }

    @Timeout
    @Lock(LockType.READ)
    public void onTimeout(Timer timer) {
        Object info = timer.getInfo();
        
        // Distinguer entre les timers de démarrage de lobby (Long) et de suppression différée (String)
        if (info instanceof String && ((String) info).startsWith("DELETE_EMPTY_")) {
            // Timer de suppression différée d'un lobby Race Again vide
            String infoStr = (String) info;
            Long lobbyId = Long.parseLong(infoStr.substring("DELETE_EMPTY_".length()));
            emptyLobbyDeletionTimers.remove(lobbyId);
            handleEmptyRaceAgainLobbyDeletion(lobbyId);
            return;
        }
        
        // Timer de démarrage de lobby normal
        Long lobbyId = (Long) info;

        // SENTINEL : si cancelLobbyTimer a déjà retiré ce lobbyId de la map,
        // cela signifie que le timer a été annulé juste avant (ou pendant) son déclenchement.
        // On abandonne immédiatement pour éviter de lancer la course après un départ de joueur.
        Timer self = activeTimers.remove(lobbyId);
        if (self == null) {
            logger.info("Lobby {} timer was cancelled before running — aborting launch", lobbyId);
            return;
        }

        LobbyEntity lobbyEntity = lobbyDAO.find(lobbyId);

        if (lobbyEntity == null) {
            logger.debug("Lobby {} already deleted when timer fired, skipping", lobbyId);
            return;
        }

        // Double-vérification du nombre de joueurs directement en base (bypasse le cache JPA)
        // pour éviter toute race condition avec un départ concurrent de joueur.
        long liveCount = lobbyEntrantDAO.countByLobby(lobbyId);
        boolean waitForMinPlayers = parameterBO.getBoolParam("LOBBY_WAIT_FOR_MIN_PLAYERS");
        
        if (liveCount == 0) {
            // Lobby vide : toujours marquer comme supprimé pour éviter qu'il soit proposé à nouveau.
            logger.info("Lobby {} is empty when timer fired, marking as deleted", lobbyId);
            eventSessionDAO.nullifyLobbyReferences(lobbyId);
            lobbyDAO.markAsDeleted(lobbyEntity);
            EventResultBO.cleanupRaceAgainLobby(lobbyId);
            return;
        } else if (liveCount == 1) {
            if (waitForMinPlayers) {
                // Feature activée : remettre le lobby en attente au lieu de le supprimer
                logger.info("Lobby {} has only 1 player when timer fired, resetting to waiting state (feature enabled)", lobbyId);
                
                // CRITIQUE : Refetch l'entité pour avoir la vraie liste d'entrants (pas le cache)
                // Évite d'envoyer des messages XMPP à des joueurs qui ont quitté
                lobbyEntity = lobbyDAO.find(lobbyId);
                
                resetSoloLobby(lobbyEntity);
                return;
            } else {
                // Feature désactivée : l'expiration est déjà couverte par startedTime + countdown.
                logger.info("Lobby {} has only 1 player when timer fired (wait disabled), skipping isActive update", lobbyId);
                return;
            }
        }

        // Refetch complet pour avoir la liste d'entrants à jour avant le lancement
        lobbyEntity = lobbyDAO.find(lobbyId);
        List<LobbyEntrantEntity> entrants = lobbyEntity.getEntrants();

        // Filtrer les entrants non joignables (session invalide/déconnectée) pour éviter les timeouts handshake.
        List<LobbyEntrantEntity> launchableEntrants = new ArrayList<>();
        for (LobbyEntrantEntity entrant : entrants) {
            Long userId = entrant.getPersona().getUser().getId();
            try {
                TokenSessionEntity tokenSession = tokenSessionBO.findByUserId(userId);
                if (tokenSession != null) {
                    Long activeLobbyId = tokenSession.getActiveLobbyId();
                    if (activeLobbyId == null || activeLobbyId.equals(0L) || !activeLobbyId.equals(lobbyId)) {
                        logger.warn("LOBBY_LAUNCH: Skipping PersonaId={} - invalid activeLobbyId={} for launching LobbyId={}",
                            entrant.getPersona().getPersonaId(), activeLobbyId, lobbyId);
                        continue;
                    }

                    if (tokenSession.getEventSessionId() != null) {
                        logger.warn("LOBBY_LAUNCH: Skipping PersonaId={} - already in EventSessionId={}",
                            entrant.getPersona().getPersonaId(), tokenSession.getEventSessionId());
                        continue;
                    }

                    launchableEntrants.add(entrant);
                } else {
                    logger.warn("LOBBY_LAUNCH: Skipping PersonaId={} (UserId={}) - no active token session", entrant.getPersona().getPersonaId(), userId);
                }
            } catch (Exception e) {
                logger.warn("LOBBY_LAUNCH: Skipping PersonaId={} (UserId={}) - token lookup failed: {}", entrant.getPersona().getPersonaId(), userId, e.getMessage());
            }
        }

        entrants = launchableEntrants;

        if (entrants.size() < 2) {
            logger.info("LOBBY_LAUNCH: Lobby {} has only {} launchable player(s) after token filtering, aborting launch", lobbyId, entrants.size());
            if (waitForMinPlayers) {
                resetSoloLobby(lobbyEntity);
            } else {
                logger.info("LOBBY_LAUNCH: wait disabled, not updating isActive for lobby {}", lobbyId);
            }
            return;
        }
        
        logger.info("LOBBY_LAUNCH: Lobby {} countdown reached 0, launching race with {} players", 
            lobbyId, entrants.size());
        
        for (LobbyEntrantEntity entrant : entrants) {
            logger.info("LOBBY_LAUNCH: Lobby {} - Player PersonaId={} Level={} will be added to race", 
                lobbyId, entrant.getPersona().getPersonaId(), entrant.getPersona().getLevel());
        }
        
        Collections.shuffle(entrants);
        for (int idx = 0; idx < entrants.size(); idx++) {
            LobbyEntrantEntity entrant = entrants.get(idx);
            entrant.setGridIndex(idx);
            lobbyEntrantDAO.update(entrant);
        }
        XMPP_LobbyLaunchedType lobbyLaunched = new XMPP_LobbyLaunchedType();
        Entrants entrantsType = new Entrants();
        List<LobbyEntrantInfo> lobbyEntrantInfo = entrantsType.getLobbyEntrantInfo();
        XMPP_CryptoTicketsType xMPP_CryptoTicketsType = new XMPP_CryptoTicketsType();
        List<XMPP_P2PCryptoTicketType> p2pCryptoTicket = xMPP_CryptoTicketsType.getP2PCryptoTicket();
        int i = 0;
        byte numOfRacers = (byte) entrants.size();
        EventSessionEntity eventSessionEntity = new EventSessionEntity();
        eventSessionEntity.setStarted(System.currentTimeMillis());
        eventSessionEntity.setEvent(lobbyEntity.getEvent());
        eventSessionEntity.setLobby(lobbyEntity);
        eventSessionDAO.insert(eventSessionEntity);
        
        String udpRaceIp = parameterBO.getStrParam("UDP_RACE_IP");
        logger.info("LOBBY_LAUNCH: UDP server configured at {}:{}", udpRaceIp, parameterBO.getIntParam("UDP_RACE_PORT"));
        for (LobbyEntrantEntity lobbyEntrantEntity : entrants) {
            // eventDataEntity.setIsSinglePlayer(false);
            Long personaId = lobbyEntrantEntity.getPersona().getPersonaId();
            // eventDataEntity.setPersonaId(personaId);
            byte gridIndex = (byte) i;
            byte[] helloPacket = {10, 11, 12, 13};
            ByteBuffer byteBuffer = ByteBuffer.allocate(48);
            byteBuffer.put(gridIndex);
            byteBuffer.put(helloPacket);
            byteBuffer.putInt(eventSessionEntity.getId().intValue());
            byteBuffer.put(numOfRacers);
            byteBuffer.putInt(personaId.intValue());
            byte[] cryptoTicketBytes = byteBuffer.array();
            String relayCryptoTicket = Base64.getEncoder().encodeToString(cryptoTicketBytes);
            tokenSessionBO.setRelayCryptoTicket(tokenSessionBO.findByUserId(lobbyEntrantEntity.getPersona().getUser().getId()), relayCryptoTicket);

            XMPP_P2PCryptoTicketType p2pCryptoTicketType = new XMPP_P2PCryptoTicketType();
            p2pCryptoTicketType.setPersonaId(personaId);
            p2pCryptoTicketType.setSessionKey("AAAAAAAAAAAAAAAAAAAAAA==");
            p2pCryptoTicket.add(p2pCryptoTicketType);

            LobbyEntrantInfo lobbyEntrantInfoType = new LobbyEntrantInfo();
            lobbyEntrantInfoType.setPersonaId(personaId);
            lobbyEntrantInfoType.setLevel(lobbyEntrantEntity.getPersona().getLevel());
            lobbyEntrantInfoType.setHeat(1);
            lobbyEntrantInfoType.setGridIndex(i++);
            lobbyEntrantInfoType.setState(LobbyEntrantState.UNKNOWN);

            lobbyEntrantInfo.add(lobbyEntrantInfoType);
        }
        XMPP_EventSessionType xMPP_EventSessionType = new XMPP_EventSessionType();
        ChallengeType challengeType = new ChallengeType();
        challengeType.setChallengeId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        challengeType.setPattern("FFFFFFFFFFFFFFFF");
        challengeType.setLeftSize(14);
        challengeType.setRightSize(50);

        xMPP_EventSessionType.setEventId(lobbyEntity.getEvent().getId());
        xMPP_EventSessionType.setChallenge(challengeType);
        xMPP_EventSessionType.setSessionId(eventSessionEntity.getId());
        lobbyLaunched.setNewRelayServer(true);
        lobbyLaunched.setLobbyId(lobbyEntity.getId());
        lobbyLaunched.setUdpRelayHost(udpRaceIp);
        lobbyLaunched.setUdpRelayPort(parameterBO.getIntParam("UDP_RACE_PORT"));

        lobbyLaunched.setEntrants(entrantsType);

        lobbyLaunched.setEventSession(xMPP_EventSessionType);

        logger.info("LOBBY_LAUNCH: Lobby {} sending launch messages to all {} players via XMPP", 
            lobbyId, entrants.size());
        
        lobbyMessagingBO.sendRelay(lobbyLaunched, xMPP_CryptoTicketsType);
        
        logger.info("LOBBY_LAUNCH: Lobby {} race launch completed successfully. EventSessionId={}", 
            lobbyId, eventSessionEntity.getId());
        
        // CRITICAL: Marquer le lobby comme supprimé maintenant que la course est lancée
        // Cela empêche de nouvelles recherches RaceNow de le trouver
        // L'EventSession garde une référence au lobby pour le calcul des rewards
        lobbyDAO.markAsDeleted(lobbyEntity);
        logger.info("LOBBY_LAUNCH: Lobby {} marked as deleted after race launch", lobbyId);
        
        // RACE_AGAIN: Nettoyer ce lobby de la map
        EventResultBO.cleanupRaceAgainLobby(lobbyId);
    }
}
