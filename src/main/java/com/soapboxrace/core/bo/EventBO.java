/*
 * This file is part of the Soapbox Race World     public void createEventDataSession(Long personaId, Long eventSessionId) {ce code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.bo;

import com.soapboxrace.core.bo.util.RacerStatus;
import com.soapboxrace.core.dao.EventDAO;
import com.soapboxrace.core.dao.EventDataDAO;
import com.soapboxrace.core.dao.EventSessionDAO;
import com.soapboxrace.core.dao.PersonaDAO;
import com.soapboxrace.core.dao.CarDAO;
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
    private CarDAO carDAO;

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

    /**
     * Vérifie si le joueur possède une voiture autorisée pour l'événement
     * @param personaId ID du persona
     * @param eventEntity L'événement à vérifier
     * @return true si le joueur peut participer, false sinon
     */
    public boolean hasAllowedCarForEvent(Long personaId, EventEntity eventEntity) {
        String carRestriction = eventEntity.getCarRestriction();
        
        // Pas de restriction = accès libre
        if (carRestriction == null || carRestriction.trim().isEmpty()) {
            logger.debug("No car restriction for event {}, access granted", eventEntity.getId());
            return true;
        }

        // Option 1: Vérifier toutes les voitures possédées (comportement actuel)
        // List<CarEntity> playerCars = carDAO.findByPersonaId(personaId);
        
        // Option 2: Vérifier seulement la voiture active/par défaut
        CarEntity defaultCar = personaBO.getDefaultCarEntity(personaId);
        List<CarEntity> playerCars = new java.util.ArrayList<>();
        if (defaultCar != null) {
            playerCars.add(defaultCar);
        }
        
        logger.debug("Event {} has car restriction: '{}', checking ACTIVE car only: {}", 
            eventEntity.getId(), carRestriction, 
            defaultCar != null ? defaultCar.getName() : "none");
        
        // Séparer les noms de voitures autorisées
        String[] allowedCarNames = carRestriction.split(",");
        logger.debug("Allowed car names: {}", java.util.Arrays.toString(allowedCarNames));
        
        // Afficher toutes les voitures du joueur
        for (CarEntity playerCar : playerCars) {
            String playerCarName = playerCar.getName();
            logger.debug("Player has car: '{}' (ID: {})", playerCarName, playerCar.getId());
        }
        
        // Vérifier si le joueur possède au moins une voiture autorisée
        for (CarEntity playerCar : playerCars) {
            String playerCarName = playerCar.getName();
            if (playerCarName != null) {
                for (String allowedCarName : allowedCarNames) {
                    String trimmedAllowed = allowedCarName.trim();
                    String trimmedPlayer = playerCarName.trim();
                    logger.debug("Comparing '{}' vs '{}' (ignoreCase)", trimmedPlayer, trimmedAllowed);
                    if (trimmedAllowed.equalsIgnoreCase(trimmedPlayer)) {
                        logger.info("Car restriction match found! Player car '{}' matches allowed car '{}'", 
                            trimmedPlayer, trimmedAllowed);
                        return true;
                    }
                }
            }
        }
        
        logger.warn("Car restriction failed for PersonaId={}, Event={}, restriction='{}' - no matching cars found", 
            personaId, eventEntity.getId(), carRestriction);
        return false;
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
        eventDataEntity.setLeftRace(false);
        eventDataEntity.setRacerStatus(RacerStatus.IN_RACE);
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

        // SÉCURITÉ : Vérifier les restrictions de voitures spécifiques
        if (!hasAllowedCarForEvent(activePersonaId, eventEntity)) {
            logger.warn("Car restriction violation blocked: PersonaId={} tried to launch EventId={} but doesn't own required cars ({})", 
                activePersonaId, eventId, eventEntity.getCarRestriction());
            throw new EngineException(EngineExceptionCode.CarDataInvalid, true);
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
