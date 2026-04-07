/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.dao;

import com.soapboxrace.core.dao.util.LongKeyedDAO;
import com.soapboxrace.core.jpa.LobbyEntity;
import com.soapboxrace.core.jpa.LobbyEntrantEntity;
import com.soapboxrace.core.jpa.PersonaEntity;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import java.util.List;

@ApplicationScoped

@Transactional
public class LobbyEntrantDAO extends LongKeyedDAO<LobbyEntrantEntity> {

    public LobbyEntrantDAO() {
        super(LobbyEntrantEntity.class);
    }

    public void deleteByPersona(PersonaEntity personaEntity) {
        Query query = entityManager.createNamedQuery("LobbyEntrantEntity.deleteByPersona");
        query.setParameter("persona", personaEntity);
        query.executeUpdate();
    }

    public void deleteByPersonaAndLobby(PersonaEntity personaEntity, LobbyEntity lobbyEntity) {
        Query query = entityManager.createNamedQuery("LobbyEntrantEntity.deleteByPersonaAndLobby");
        query.setParameter("persona", personaEntity);
        query.setParameter("lobby", lobbyEntity);
        query.executeUpdate();
    }

    public void updateVoteByPersonaAndLobby(PersonaEntity personaEntity, LobbyEntity lobbyEntity) {
        Query query = entityManager.createNamedQuery("LobbyEntrantEntity.updateVoteByPersonaAndLobby");
        query.setParameter("persona", personaEntity);
        query.setParameter("lobby", lobbyEntity);
        query.executeUpdate();
    }

    public LobbyEntrantEntity getVoteStatus(PersonaEntity personaEntity, LobbyEntity lobbyEntity) {
        TypedQuery<LobbyEntrantEntity> query = entityManager.createNamedQuery("LobbyEntrantEntity.getVoteStatus", LobbyEntrantEntity.class);
        query.setParameter("persona", personaEntity);
        query.setParameter("lobby", lobbyEntity);

        List<LobbyEntrantEntity> resultList = query.getResultList();
        return !resultList.isEmpty() ? resultList.get(0) : null;
    }

    public int getVotes(LobbyEntity lobby) {
        TypedQuery<Long> query = entityManager.createNamedQuery("LobbyEntrantEntity.getVotes", Long.class);
        query.setParameter("lobby", lobby);

        return query.getSingleResult().intValue();
    }

    /**
     * Compte le nombre d'entrants d'un lobby directement en base, sans passer par le cache JPA.
     * Utilisé dans onTimeout pour obtenir un décompte fiable avant de lancer la course.
     */
    public long countByLobby(Long lobbyId) {
        return entityManager.createQuery(
            "SELECT COUNT(e) FROM LobbyEntrantEntity e WHERE e.lobby.id = :lobbyId",
            Long.class
        ).setParameter("lobbyId", lobbyId).getSingleResult();
    }

    /**
     * Compte le nombre d'entrées d'un persona dans un lobby (doit être 0 ou 1 en fonctionnement normal).
     */
    public long countByPersonaAndLobby(Long personaId, Long lobbyId) {
        return entityManager.createQuery(
            "SELECT COUNT(e) FROM LobbyEntrantEntity e WHERE e.persona.personaId = :personaId AND e.lobby.id = :lobbyId",
            Long.class
        ).setParameter("personaId", personaId)
         .setParameter("lobbyId", lobbyId)
         .getSingleResult();
    }

    /**
     * Retourne les IDs des lobbies actifs dans lesquels un persona est présent.
     */
    public List<Long> findActiveLobbyIdsByPersona(Long personaId) {
        return entityManager.createQuery(
            "SELECT DISTINCT e.lobby.id FROM LobbyEntrantEntity e WHERE e.persona.personaId = :personaId AND e.lobby.isActive = true",
            Long.class
        ).setParameter("personaId", personaId).getResultList();
    }
}
