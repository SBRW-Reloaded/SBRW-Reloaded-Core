/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.bo;

import com.soapboxrace.core.dao.EventDAO;
import com.soapboxrace.core.jpa.EventEntity;
import com.soapboxrace.core.jpa.EventSessionEntity;
import com.soapboxrace.core.jpa.LobbyEntity;

import javax.annotation.Resource;
import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.ejb.TransactionManagement;
import javax.ejb.TransactionManagementType;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.transaction.UserTransaction;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

/**
 * Service pour gérer la création atomique des lobbies Race Again.
 * Garantit que tous les joueurs d'une même session rejoignent LE MÊME lobby sur LE MÊME événement.
 */
@Stateless
@TransactionManagement(TransactionManagementType.BEAN)
public class RaceAgainBO {
    
    private static final Logger logger = Logger.getLogger(RaceAgainBO.class.getName());
    private static final Random random = new Random();
    
    @PersistenceContext
    private EntityManager em;
    
    @Resource
    private UserTransaction userTransaction;
    
    @EJB
    private LobbyBO lobbyBO;
    
    @EJB
    private EventDAO eventDAO;
    
    /**
     * Récupère ou crée un lobby Race Again pour une session d'événement.
     * LA SÉLECTION DE L'ÉVÉNEMENT SE FAIT ICI, PAS AVANT !
     * 
     * @param eventSessionEntity La session d'événement
     * @param personaId ID du joueur
     * @param carClassHash Classe de la voiture du joueur
     * @param eventModeId Mode de l'événement actuel
     * @param personaLevel Niveau du joueur
     * @return Résultat contenant le lobby ET l'événement sélectionné
     */
    public RaceAgainResult getOrCreateRaceAgainLobby(EventSessionEntity eventSessionEntity, Long personaId, 
                                                      int carClassHash, int eventModeId, int personaLevel) {
        Long eventSessionId = eventSessionEntity.getId();
        String thread = Thread.currentThread().getName();
        logger.info(String.format(">>> Thread %s requesting Race Again lobby for EventSession %d", thread, eventSessionId));
        
        LobbyEntity lobby = null;
        EventEntity selectedEvent = null;
        
        try {
            userTransaction.begin();
            
            // Clear cache pour forcer lecture fraîche
            em.clear();
            
            // Set isolation level BEFORE any query
            em.createNativeQuery("SET TRANSACTION ISOLATION LEVEL READ COMMITTED").executeUpdate();
            
            // SELECT ... FOR UPDATE pour verrouiller la ligne
            List<?> result = em.createNativeQuery(
                "SELECT NEXTLOBBYID, NEXTEVENTID FROM EVENT_SESSION WHERE ID = ? FOR UPDATE"
            ).setParameter(1, eventSessionId).getResultList();
            
            Long existingLobbyId = null;
            Long existingEventId = null;
            if (!result.isEmpty()) {
                Object[] row = (Object[]) result.get(0);
                existingLobbyId = row[0] != null ? ((Number) row[0]).longValue() : null;
                existingEventId = row[1] != null ? ((Number) row[1]).longValue() : null;
            }
            
            logger.info(String.format(">>> Thread %s - Lock acquired, NEXTLOBBYID=%s, NEXTEVENTID=%s",
                thread, existingLobbyId, existingEventId));
            
            if (existingLobbyId != null && existingEventId != null) {
                // Lobby ET événement existent déjà
                lobby = em.find(LobbyEntity.class, existingLobbyId);
                selectedEvent = em.find(EventEntity.class, existingEventId);
                logger.info(String.format(">>> Thread %s - REUSING existing lobby %d + event %d from EventSession %d",
                    thread, existingLobbyId, existingEventId, eventSessionId));
            } else {
                // Sélectionner un événement aléatoire ICI
                logger.info(String.format(">>> Thread %s - SELECTING random event for EventSession %d",
                    thread, eventSessionId));
                
                List<EventEntity> eligibleEvents = eventDAO.findEligibleForRaceAgainByMode(
                    personaLevel, 
                    carClassHash, 
                    eventModeId
                );
                
                if (eligibleEvents == null || eligibleEvents.isEmpty()) {
                    throw new IllegalStateException("No eligible events found for Race Again");
                }
                
                selectedEvent = eligibleEvents.get(random.nextInt(eligibleEvents.size()));
                logger.info(String.format(">>> Thread %s - SELECTED random event %d (mode %d) from %d candidates",
                    thread, selectedEvent.getId(), selectedEvent.getEventModeId(), eligibleEvents.size()));
                
                if (selectedEvent.getId() <= 0) {
                    throw new IllegalStateException("Selected event has invalid ID: " + selectedEvent.getId());
                }
                
                // Créer un nouveau lobby
                logger.info(String.format(">>> Thread %s - CREATING new lobby for EventSession %d on Event %d",
                    thread, eventSessionId, selectedEvent.getId()));
                lobby = lobbyBO.createLobby(personaId, selectedEvent.getId(), carClassHash, false);
                em.flush();
                
                // UPDATE avec native query (NEXTLOBBYID + NEXTEVENTID)
                int rowsAffected = em.createNativeQuery(
                    "UPDATE EVENT_SESSION SET NEXTLOBBYID = ?, NEXTEVENTID = ? WHERE ID = ?"
                ).setParameter(1, lobby.getId())
                 .setParameter(2, selectedEvent.getId())
                 .setParameter(3, eventSessionId)
                 .executeUpdate();
                
                logger.info(String.format(">>> Thread %s - UPDATE executed, rows affected: %d, lobbyId=%d, eventId=%d",
                    thread, rowsAffected, lobby.getId(), selectedEvent.getId()));
                
                if (rowsAffected == 0) {
                    throw new IllegalStateException("UPDATE failed: no rows affected for EventSession " + eventSessionId);
                }
            }
            
            // CRITICAL: Flush BEFORE commit to ensure UPDATE is sent to database
            em.flush();
            
            // Commit de la transaction
            userTransaction.commit();
            logger.info(String.format(">>> Thread %s - Transaction COMMITTED", thread));
            
            // Vérification
            userTransaction.begin();
            Object[] verifyResult = (Object[]) em.createNativeQuery(
                "SELECT NEXTLOBBYID, NEXTEVENTID FROM EVENT_SESSION WHERE ID = ?"
            ).setParameter(1, eventSessionId).getSingleResult();
            userTransaction.commit();
            
            Long verifiedLobbyId = verifyResult[0] != null ? ((Number) verifyResult[0]).longValue() : null;
            Long verifiedEventId = verifyResult[1] != null ? ((Number) verifyResult[1]).longValue() : null;
            logger.info(String.format(">>> Thread %s - VERIFICATION: NEXTLOBBYID=%s (expected %s), NEXTEVENTID=%s (expected %s)",
                thread, verifiedLobbyId, lobby.getId(), verifiedEventId, selectedEvent.getId()));
            
            if (existingLobbyId == null) {
                logger.info(String.format(">>> Thread %s - NEW lobby %d + event %d COMMITTED to EventSession %d",
                    thread, lobby.getId(), selectedEvent.getId(), eventSessionId));
            }
            
        } catch (Exception e) {
            logger.severe(String.format(">>> Thread %s - ERROR: %s", thread, e.getMessage()));
            try {
                userTransaction.rollback();
            } catch (Exception rollbackEx) {
                logger.severe("Failed to rollback transaction: " + rollbackEx.getMessage());
            }
            throw new RuntimeException("Failed to create/retrieve Race Again lobby", e);
        }
        
        return new RaceAgainResult(lobby, selectedEvent);
    }
    
    /**
     * Classe contenant le résultat de la création/récupération du lobby Race Again.
     */
    public static class RaceAgainResult {
        private final LobbyEntity lobby;
        private final EventEntity event;
        
        public RaceAgainResult(LobbyEntity lobby, EventEntity event) {
            this.lobby = lobby;
            this.event = event;
        }
        
        public LobbyEntity getLobby() {
            return lobby;
        }
        
        public EventEntity getEvent() {
            return event;
        }
    }
}
