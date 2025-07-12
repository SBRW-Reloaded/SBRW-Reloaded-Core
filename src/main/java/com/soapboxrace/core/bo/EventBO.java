/*
 * This file is part of the Soapbox Race World     public void createEventDataSession(Long personaId, Long eventSessionId) {ce code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.bo;

import com.soapboxrace.core.dao.EventDAO;
import com.soapboxrace.core.dao.EventDataDAO;
import com.soapboxrace.core.dao.EventSessionDAO;
import com.soapboxrace.core.dao.PersonaDAO;
import com.soapboxrace.core.engine.EngineException;
import com.soapboxrace.core.engine.EngineExceptionCode;
import com.soapboxrace.core.jpa.*;
import org.hibernate.Hibernate;
import org.slf4j.Logger;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.inject.Inject;
import java.util.List;
import java.util.Objects;


@Stateless
public class EventBO {

    @EJB
    private EventDAO eventDao;

    @EJB
    private EventSessionDAO eventSessionDao;

    @EJB
    private EventDataDAO eventDataDao;

    @EJB
    private PersonaDAO personaDao;

    @EJB
    private PersonaBO personaBO;

    @Inject
    private Logger logger;

    public List<EventEntity> availableAtLevel(Long personaId) {
        PersonaEntity personaEntity = personaDao.find(personaId);
        return eventDao.findByLevel(personaEntity.getLevel());
    }

    /**
     * Récupère tous les événements disponibles (activés)
     * @return Liste de tous les événements activés
     */
    public List<EventEntity> getAllEvents() {
        return eventDao.findAll();
    }

    public void createEventDataSession(Long personaId, Long eventSessionId) {
        CarEntity carEntity = personaBO.getDefaultCarEntity(personaId);

        EventSessionEntity eventSessionEntity = eventSessionDao.find(eventSessionId);
        EventDataEntity eventDataEntity = new EventDataEntity();
        eventDataEntity.setPersonaId(personaId);
        eventDataEntity.setEventSessionId(eventSessionId);
        eventDataEntity.setEvent(eventSessionEntity.getEvent());
        eventDataEntity.setServerTimeStarted(System.currentTimeMillis());
        eventDataEntity.setCarClassHash(carEntity.getCarClassHash());
        eventDataEntity.setCarRating(carEntity.getRating());
        eventDataDao.insert(eventDataEntity);
    }

    public EventSessionEntity createEventSession(TokenSessionEntity tokenSessionEntity, int eventId) {
        Objects.requireNonNull(tokenSessionEntity);

        EventEntity eventEntity = eventDao.find(eventId);
        if (eventEntity == null) {
            return null;
        }

        Long activePersonaId = tokenSessionEntity.getActivePersonaId();

        if (activePersonaId.equals(0L)) {
            return null;
        }

        // SÉCURITÉ : Vérifier le niveau du joueur AVANT de créer la session d'événement
        PersonaEntity personaEntity = personaDao.find(activePersonaId);
        if (personaEntity.getLevel() < eventEntity.getMinLevel() || personaEntity.getLevel() > eventEntity.getMaxLevel()) {
            logger.warn("Level restriction violation blocked: PersonaId={} (Level={}) tried to launch EventId={} (MinLevel={}, MaxLevel={})", 
                activePersonaId, personaEntity.getLevel(), eventId, eventEntity.getMinLevel(), eventEntity.getMaxLevel());
            throw new EngineException(EngineExceptionCode.InvalidEntrantEventSession, true);
        }

        CarEntity carEntity = personaBO.getDefaultCarEntity(activePersonaId);

        // Vérification du niveau du joueur et de la classe de voiture - traiter comme une restriction de classe
        if (carEntity.getCarClassHash() == 0 || 
            (eventEntity.getCarClassHash() != 607077938 && carEntity.getCarClassHash() != eventEntity.getCarClassHash()) ||
            (personaEntity.getLevel() < eventEntity.getMinLevel() || personaEntity.getLevel() > eventEntity.getMaxLevel())) {
            // The client UI does not allow you to join events outside your current car's class or level range
            throw new EngineException(EngineExceptionCode.CarDataInvalid, false);
        }

        EventSessionEntity eventSessionEntity = new EventSessionEntity();
        eventSessionEntity.setEvent(eventEntity);
        eventSessionEntity.setStarted(System.currentTimeMillis());
        eventSessionEntity.setNopuMode(false);
        eventSessionDao.insert(eventSessionEntity);
        return eventSessionEntity;
    }

    public EventSessionEntity findEventSessionById(Long id) {
        EventSessionEntity eventSession = eventSessionDao.find(id);
        Hibernate.initialize(eventSession.getEvent().getSingleplayerRewardConfig());
        Hibernate.initialize(eventSession.getEvent().getMultiplayerRewardConfig());
        Hibernate.initialize(eventSession.getEvent().getPrivateRewardConfig());
        return eventSession;
    }
}
