/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.dao;

import com.soapboxrace.core.dao.util.LongKeyedDAO;
import com.soapboxrace.core.jpa.EventSessionEntity;
import com.soapboxrace.core.jpa.LobbyEntity;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import javax.persistence.LockModeType;
import javax.persistence.TypedQuery;
import java.util.List;

@ApplicationScoped

@Transactional
public class EventSessionDAO extends LongKeyedDAO<EventSessionEntity> {

    public EventSessionDAO() {
        super(EventSessionEntity.class);
    }
    
    /**
     * Find EventSession with PESSIMISTIC_WRITE lock to prevent race conditions
     * when multiple threads try to create Race Again lobby for the same session.
     */
    public EventSessionEntity findWithLock(Long id) {
        return this.entityManager.find(EventSessionEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
    }
    
    /**
     * Update EventSession and immediately flush to database.
     * This ensures the changes are visible to other threads waiting on PESSIMISTIC_WRITE lock.
     */
    public void updateAndFlush(EventSessionEntity entity) {
        this.entityManager.merge(entity);
        this.entityManager.flush();
    }

    /**
     * Annule les références lobby/nextLobby d'un lobby supprimé dans toutes les EventSession liées.
     * À appeler avant lobbyDAO.markAsDeleted() pour éviter les références orphelines.
     */
    public void nullifyLobbyReferences(Long lobbyId) {
        entityManager.createQuery(
            "UPDATE EventSessionEntity e SET e.lobby = null WHERE e.lobby.id = :lobbyId"
        ).setParameter("lobbyId", lobbyId).executeUpdate();
        entityManager.createQuery(
            "UPDATE EventSessionEntity e SET e.nextLobby = null WHERE e.nextLobby.id = :lobbyId"
        ).setParameter("lobbyId", lobbyId).executeUpdate();
    }
    
    /**
     * Trouve toutes les EventSession associées à un lobby donné.
     * Utilisé pour détecter si une course a déjà été lancée pour ce lobby.
     */
    public List<EventSessionEntity> findByLobby(LobbyEntity lobby) {
        TypedQuery<EventSessionEntity> query = entityManager.createQuery(
            "SELECT e FROM EventSessionEntity e WHERE e.lobby = :lobby", 
            EventSessionEntity.class
        );
        query.setParameter("lobby", lobby);
        return query.getResultList();
    }

    public EventSessionEntity findByNextLobbyId(Long nextLobbyId) {
        TypedQuery<EventSessionEntity> query = entityManager.createQuery(
            "SELECT e FROM EventSessionEntity e WHERE e.nextLobby.id = :nextLobbyId",
            EventSessionEntity.class
        );
        query.setParameter("nextLobbyId", nextLobbyId);
        List<EventSessionEntity> results = query.getResultList();
        return results.isEmpty() ? null : results.get(0);
    }
}
