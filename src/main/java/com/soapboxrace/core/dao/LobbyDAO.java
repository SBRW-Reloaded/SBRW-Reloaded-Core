/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.dao;

import com.soapboxrace.core.bo.ParameterBO;
import com.soapboxrace.core.dao.util.LongKeyedDAO;
import com.soapboxrace.core.jpa.EventEntity;
import com.soapboxrace.core.jpa.LobbyEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.persistence.LockModeType;
import javax.persistence.TypedQuery;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@ApplicationScoped

@Transactional
public class LobbyDAO extends LongKeyedDAO<LobbyEntity> {
    
    private static final Logger logger = LoggerFactory.getLogger(LobbyDAO.class);
    
    @Inject
    private ParameterBO parameterBO;

    public LobbyDAO() {
        super(LobbyEntity.class);
    }

    /**
     * Récupère le temps de fenêtre de recherche de lobbies depuis le paramètre DB.
     * Utilisé pour déterminer combien de temps un lobby reste visible dans les recherches.
     */
    private int getLobbySearchWindowSeconds() {
        // Essayer de lire le paramètre LOBBY_COUNTDOWN_TIME
        int countdownMs = parameterBO.getIntParam("LOBBY_COUNTDOWN_TIME", 0);
        
        if (countdownMs > 0) {
            // Utiliser la même valeur que le countdown réel
            return countdownMs / 1000;
        } else {
            // Fallback à 35 secondes (valeur originale du code exported)
            return 35;
        }
    }

    public LobbyEntity findById(Long id) {
        LobbyEntity lobbyEntity = entityManager.find(LobbyEntity.class, id);

        if (lobbyEntity != null) {
            lobbyEntity.getEntrants().size();
            return lobbyEntity;
        }

        return null;
    }

    /**
     * Find a lobby by ID with a PESSIMISTIC_WRITE lock (SELECT ... FOR UPDATE).
     * Prevents race conditions when multiple players try to join the same lobby concurrently.
     * Any concurrent transaction trying to lock the same lobby will block until this one commits.
     */
    public LobbyEntity findWithLock(Long id) {
        LobbyEntity lobbyEntity = entityManager.find(LobbyEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (lobbyEntity != null) {
            lobbyEntity.getEntrants().size();
        }
        return lobbyEntity;
    }

    public List<LobbyEntity> findAllOpen(int carClassHash, int level) {
        LocalDateTime dateNow = LocalDateTime.now();
        LocalDateTime datePast = LocalDateTime.now().minusSeconds(getLobbySearchWindowSeconds());

        logger.info("LOBBYDAO: findAllOpen called with carClassHash={}, level={}, timeRange=[{} to {}]", 
            carClassHash, level, datePast, dateNow);

        // Utilise la NamedQuery définie dans LobbyEntity qui inclut déjà NOT EXISTS
        TypedQuery<LobbyEntity> query = entityManager.createNamedQuery(
            "LobbyEntity.findAllOpenByCarClass", 
            LobbyEntity.class
        );
        query.setParameter("dateTime1", datePast);
        query.setParameter("dateTime2", dateNow);
        query.setParameter("carClassHash", carClassHash);
        query.setParameter("level", level);
        
        List<LobbyEntity> results = query.getResultList();
        
        logger.info("LOBBYDAO: SQL query returned {} lobbies before lockedCarClassHash filtering", results.size());
        
        // DIAGNOSTIC: Afficher TOUS les lobbies retournés par SQL
        for (LobbyEntity lobby : results) {
            logger.info("LOBBYDAO SQL RESULT: LobbyId={}, EventId={}, Entrants={}, StartedTime={}, LockedClass={}, EventClass={}", 
                lobby.getId(), 
                lobby.getEvent().getId(),
                lobby.getEntrants().size(),
                lobby.getStartedTime(),
                lobby.getLockedCarClassHash(),
                lobby.getEvent().getCarClassHash());
        }
        
        // Filtrer uniquement par lockedCarClassHash (pas par event car class)
        // Cela permet aux joueurs avec différentes car classes de rejoindre des lobbies publics non-verrouillés
        List<LobbyEntity> filtered = new java.util.ArrayList<>();
        for (LobbyEntity lobby : results) {
            // Si le lobby est verrouillé à une car class, vérifier la correspondance
            if (lobby.getLockedCarClassHash() != null && lobby.getLockedCarClassHash() != carClassHash) {
                logger.debug("LOBBYDAO: Filtering lobby {} - locked to carClass={}, player has carClass={}", 
                    lobby.getId(), lobby.getLockedCarClassHash(), carClassHash);
                continue;
            }
            filtered.add(lobby);
        }
        
        logger.info("LOBBYDAO: findAllOpen returned {} results after lock filtering ({} before filtering)", 
            filtered.size(), results.size());
        
        return filtered;
    }

    /**
     * Trouve tous les lobbies ouverts par niveau uniquement (sans filtrage de classe de voiture).
     * Utilisé par RaceNowMonitor pour ensuite filtrer manuellement selon la classe verrouillée.
     */
    public List<LobbyEntity> findAllOpenByLevel(int level) {
        LocalDateTime dateNow = LocalDateTime.now();
        LocalDateTime datePast = LocalDateTime.now().minusSeconds(getLobbySearchWindowSeconds());

        TypedQuery<LobbyEntity> query = entityManager.createNamedQuery(
            "LobbyEntity.findAllOpenByLevel", 
            LobbyEntity.class
        );
        query.setParameter("dateTime1", datePast);
        query.setParameter("dateTime2", dateNow);
        query.setParameter("level", level);
        return query.getResultList();
    }

    public List<LobbyEntity> findAllEmptyOlderThan(LocalDateTime cutoff) {
        return entityManager.createQuery(
            "SELECT obj FROM LobbyEntity obj WHERE obj.isActive = true AND obj.startedTime < :cutoff AND size(obj.entrants) = 0",
            LobbyEntity.class
        ).setParameter("cutoff", cutoff).getResultList();
    }

    /**
     * Trouve tous les lobbies publics plus anciens qu'un certain temps (pour cleanup)
     * Utilisé pour nettoyer les lobbies abandonnés où les joueurs ont déconnecté sans quitter
     */
    public List<LobbyEntity> findAllOlderThan(LocalDateTime cutoff) {
        return entityManager.createQuery(
            "SELECT obj FROM LobbyEntity obj WHERE obj.isActive = true AND obj.isPrivate = false AND obj.startedTime < :cutoff",
            LobbyEntity.class
        ).setParameter("cutoff", cutoff).getResultList();
    }

    /**
     * Trouve tous les lobbies publics avec 0 ou 1 joueur
     * Utilisé pour rafraîchir les lobbies en attente dans la feature LOBBY_WAIT_FOR_MIN_PLAYERS
     * et nettoyer les lobbies jamais acceptés
     */
    public List<LobbyEntity> findAllWithOnePlayer() {
        logger.info("FINDONEPLAYERDAO: Executing query to find lobbies with 0 or 1 player");
        
        // Première approche : charger tous les lobbies publics actifs et filtrer en Java
        List<LobbyEntity> allLobbies = entityManager.createQuery(
            "SELECT obj FROM LobbyEntity obj WHERE obj.isActive = true AND obj.isPrivate = false",
            LobbyEntity.class
        ).getResultList();
        
        logger.info("FINDONEPLAYERDAO: Found {} public lobbies total", allLobbies.size());
        
        List<LobbyEntity> result = new ArrayList<>();
        for (LobbyEntity lobby : allLobbies) {
            int entrantCount = lobby.getEntrants().size();
            logger.info("FINDONEPLAYERDAO: Lobby ID={}, isPrivate={}, entrants={}, startedTime={}", 
                lobby.getId(), lobby.getIsPrivate(), entrantCount, lobby.getStartedTime());
            
            if (entrantCount <= 1) {
                result.add(lobby);
                logger.info("FINDONEPLAYERDAO: -> Lobby {} MATCHES criteria ({} player(s))", lobby.getId(), entrantCount);
            }
        }
        
        logger.info("FINDONEPLAYERDAO: Returning {} lobbies with exactly 1 player", result.size());
        return result;
    }

    public List<LobbyEntity> findByEventStarted(EventEntity eventEntity) {
        LocalDateTime dateNow = LocalDateTime.now();
        LocalDateTime datePast = LocalDateTime.now().minus(eventEntity.getLobbyCountdownTime(), TimeUnit.MILLISECONDS.toChronoUnit());

        TypedQuery<LobbyEntity> query = entityManager.createNamedQuery("LobbyEntity.findByEventStarted",
                LobbyEntity.class);
        query.setParameter("event", eventEntity);
        query.setParameter("dateTime1", datePast);
        query.setParameter("dateTime2", dateNow);
        return query.getResultList();
    }

    public LobbyEntity findByEventAndPersona(EventEntity eventEntity, Long personaId) {
        LocalDateTime dateNow = LocalDateTime.now();
        LocalDateTime datePast = LocalDateTime.now().minus(eventEntity.getLobbyCountdownTime(), TimeUnit.MILLISECONDS.toChronoUnit());

        TypedQuery<LobbyEntity> query = entityManager.createNamedQuery("LobbyEntity.findByEventAndPersona",
                LobbyEntity.class);
        query.setParameter("event", eventEntity);
        query.setParameter("dateTime1", datePast);
        query.setParameter("dateTime2", dateNow);
        query.setParameter("personaId", personaId);

        List<LobbyEntity> resultList = query.getResultList();
        return !resultList.isEmpty() ? resultList.get(0) : null;
    }

    /**
     * Force un rechargement de l'entité depuis la base de données, invalidant le cache L1 JPA.
     * Nécessaire après un bulk DELETE (JPQL) pour obtenir la liste d'entrants à jour.
     */
    public void refresh(LobbyEntity entity) {
        entityManager.refresh(entity);
    }
    
    /**
     * Marque un lobby comme supprimé (soft delete) au lieu de le supprimer physiquement.
     * Cela permet de conserver l'historique pour l'audit et le debugging.
     * Le lobby devient invisible pour toutes les queries (filtré par isActive = true).
     * 
     * @param lobbyEntity Le lobby à marquer comme supprimé
     */
    public void markAsDeleted(LobbyEntity lobbyEntity) {
        if (lobbyEntity != null) {
            lobbyEntity.setIsActive(false);
            update(lobbyEntity);
            logger.info("Lobby {} marked as deleted (soft delete)", lobbyEntity.getId());
        }
    }
    
    /**
     * Marque un lobby comme supprimé par son ID (soft delete).
     * 
     * @param lobbyId L'ID du lobby à marquer comme supprimé
     */
    public void markAsDeletedById(Long lobbyId) {
        LobbyEntity lobby = find(lobbyId);
        if (lobby != null) {
            markAsDeleted(lobby);
        }
    }
}
