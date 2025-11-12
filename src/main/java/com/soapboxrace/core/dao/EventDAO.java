/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.dao;

import com.soapboxrace.core.dao.util.BaseDAO;
import com.soapboxrace.core.jpa.EventEntity;

import javax.ejb.Stateless;
import javax.persistence.TypedQuery;
import java.util.List;

@Stateless
public class EventDAO extends BaseDAO<EventEntity, Integer> {

    @Override
    public EventEntity find(Integer id) {
        return entityManager.find(EventEntity.class, id);
    }

    public List<EventEntity> findAll() {
        TypedQuery<EventEntity> query = entityManager.createNamedQuery("EventEntity.findAll", EventEntity.class);
        return query.getResultList();
    }

    public List<EventEntity> findAllRotatable() {
        TypedQuery<EventEntity> query = entityManager.createNamedQuery("EventEntity.findAllRotatable", EventEntity.class);
        return query.getResultList();
    }

    public List<EventEntity> findByLevel(int level) {
        TypedQuery<EventEntity> query = entityManager.createNamedQuery("EventEntity.findByLevel", EventEntity.class);
        query.setParameter("level", level);
        return query.getResultList();
    }

    /**
     * Trouve les événements éligibles pour la création automatique de lobby RaceNow
     * Retourne les événements activés avec eventModeId 4 (Circuit) ou 9 (Sprint)
     * qui correspondent au niveau et à la classe de voiture du joueur
     * 
     * @param level Le niveau du joueur
     * @param carClassHash Le hash de la classe de voiture du joueur
     * @return Liste des événements éligibles (OpenClass ou classe correspondante)
     */
    public List<EventEntity> findEligibleForAutoLobby(int level, int carClassHash) {
        TypedQuery<EventEntity> query = entityManager.createQuery(
            "SELECT obj FROM EventEntity obj WHERE obj.isEnabled = true " +
            "AND (obj.eventModeId = 4 OR obj.eventModeId = 9) " +
            "AND :level >= obj.minLevel AND :level <= obj.maxLevel " +
            "AND (obj.carClassHash = 607077938 OR obj.carClassHash = :carClassHash)",
            EventEntity.class
        );
        query.setParameter("level", level);
        query.setParameter("carClassHash", carClassHash);
        return query.getResultList();
    }

    /**
     * Trouve les événements éligibles pour Race Again
     * Retourne les événements activés (tous les modes SAUF 22 (Drag) et 12)
     * qui correspondent au niveau et à la classe de voiture du joueur
     * 
     * @param level Le niveau du joueur
     * @param carClassHash Le hash de la classe de voiture du joueur
     * @return Liste des événements éligibles
     */
    public List<EventEntity> findEligibleForRaceAgain(int level, int carClassHash) {
        TypedQuery<EventEntity> query = entityManager.createQuery(
            "SELECT obj FROM EventEntity obj WHERE obj.isEnabled = true " +
            "AND obj.eventModeId != 22 AND obj.eventModeId != 12 " +
            "AND :level >= obj.minLevel AND :level <= obj.maxLevel " +
            "AND (obj.carClassHash = 607077938 OR obj.carClassHash = :carClassHash)",
            EventEntity.class
        );
        query.setParameter("level", level);
        query.setParameter("carClassHash", carClassHash);
        return query.getResultList();
    }

    /**
     * Trouve les événements éligibles pour Race Again filtré par mode
     * Si eventModeId est 4 ou 9, retourne les événements avec mode 4 OU 9
     * Sinon, retourne uniquement les événements avec le mode exact
     * 
     * @param level Le niveau du joueur
     * @param carClassHash Le hash de la classe de voiture du joueur
     * @param eventModeId Le mode de l'événement précédent
     * @return Liste des événements éligibles du même mode
     */
    public List<EventEntity> findEligibleForRaceAgainByMode(int level, int carClassHash, int eventModeId) {
        String modeFilter;
        
        // Si mode 4 (Circuit) ou 9 (Sprint), accepter les deux
        if (eventModeId == 4 || eventModeId == 9) {
            modeFilter = "(obj.eventModeId = 4 OR obj.eventModeId = 9)";
        } else {
            modeFilter = "obj.eventModeId = :eventModeId";
        }
        
        TypedQuery<EventEntity> query = entityManager.createQuery(
            "SELECT obj FROM EventEntity obj WHERE obj.isEnabled = true " +
            "AND " + modeFilter + " " +
            "AND :level >= obj.minLevel AND :level <= obj.maxLevel " +
            "AND (obj.carClassHash = 607077938 OR obj.carClassHash = :carClassHash)",
            EventEntity.class
        );
        query.setParameter("level", level);
        query.setParameter("carClassHash", carClassHash);
        
        // Seulement set eventModeId si ce n'est pas 4 ou 9
        if (eventModeId != 4 && eventModeId != 9) {
            query.setParameter("eventModeId", eventModeId);
        }
        
        return query.getResultList();
    }

}
