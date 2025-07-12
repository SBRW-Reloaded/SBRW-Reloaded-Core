/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.api;

import com.soapboxrace.core.bo.PresenceBO;
import com.soapboxrace.core.bo.SocialRelationshipBO;
import com.soapboxrace.core.dao.SocialRelationshipDAO;
import com.soapboxrace.core.jpa.PersonaEntity;
import com.soapboxrace.core.jpa.SocialRelationshipEntity;
import org.slf4j.Logger;

import javax.ejb.EJB;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

/**
 * AMÉLIORATION: Endpoint d'administration pour diagnostiquer et améliorer 
 * les problèmes de présence dans les listes d'amis.
 * Utilisé pour surveiller et déboguer le système de présence des amis.
 */
@Path("/admin/friends-presence")
public class FriendsPresenceAdmin {

    @EJB
    private PresenceBO presenceBO;

    @EJB
    private SocialRelationshipBO socialRelationshipBO;

    @EJB
    private SocialRelationshipDAO socialRelationshipDAO;

    @Inject
    private Logger logger;

    /**
     * AMÉLIORATION: Retourne des informations détaillées sur l'état des présences
     * dans les listes d'amis pour aider au debugging
     */
    @GET
    @Path("/debug")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getFriendsPresenceDebugInfo() {
        try {
            StringBuilder info = new StringBuilder();
            info.append("Friends Presence Debug Info:\n");
            info.append("================================\n\n");

            // Obtenir toutes les relations d'amis
            List<SocialRelationshipEntity> friendships = socialRelationshipDAO.findByStatus(1L);
            info.append("Total friendships: ").append(friendships.size()).append("\n\n");

            if (friendships.isEmpty()) {
                info.append("No friendships found.\n");
                return Response.ok(info.toString()).build();
            }

            // Analyser les présences des amis
            int totalFriendPersonas = 0;
            int onlineFriends = 0;
            int inRaceFriends = 0;
            int offlineFriends = 0;
            int deletedPersonas = 0;

            for (SocialRelationshipEntity friendship : friendships) {
                for (PersonaEntity persona : friendship.getRemoteUser().getPersonas()) {
                    if (persona.getDeletedAt() != null) {
                        deletedPersonas++;
                        continue;
                    }

                    totalFriendPersonas++;
                    Long presence = presenceBO.getPresence(persona.getPersonaId());

                    if (presence.equals(PresenceBO.PRESENCE_ONLINE)) {
                        onlineFriends++;
                    } else if (presence.equals(PresenceBO.PRESENCE_IN_RACE)) {
                        inRaceFriends++;
                    } else {
                        offlineFriends++;
                    }
                }
            }

            info.append("Friend Personas Statistics:\n");
            info.append("- Total active personas: ").append(totalFriendPersonas).append("\n");
            info.append("- Online friends: ").append(onlineFriends).append("\n");
            info.append("- In race friends: ").append(inRaceFriends).append("\n");
            info.append("- Offline friends: ").append(offlineFriends).append("\n");
            info.append("- Deleted personas: ").append(deletedPersonas).append("\n\n");

            // Calculer les pourcentages
            if (totalFriendPersonas > 0) {
                double onlinePercentage = (double) onlineFriends / totalFriendPersonas * 100;
                double inRacePercentage = (double) inRaceFriends / totalFriendPersonas * 100;
                double offlinePercentage = (double) offlineFriends / totalFriendPersonas * 100;

                info.append("Presence Distribution:\n");
                info.append("- Online: ").append(String.format("%.1f%%", onlinePercentage)).append("\n");
                info.append("- In Race: ").append(String.format("%.1f%%", inRacePercentage)).append("\n");
                info.append("- Offline: ").append(String.format("%.1f%%", offlinePercentage)).append("\n");
            }

            logger.info("Friends presence debug info requested");
            return Response.ok(info.toString()).build();
            
        } catch (Exception e) {
            logger.error("Error retrieving friends presence debug info", e);
            return Response.serverError()
                    .entity("Error retrieving friends presence debug info: " + e.getMessage())
                    .build();
        }
    }

    /**
     * AMÉLIORATION: Force un rafraîchissement manuel des présences des amis
     */
    @POST
    @Path("/refresh")
    @Produces(MediaType.TEXT_PLAIN)
    public Response forceRefreshFriendsPresence() {
        try {
            logger.info("Manual friends presence refresh requested");
            
            // Appeler la méthode de nettoyage des présences des amis
            socialRelationshipBO.cleanupFriendsPresences();
            
            String result = "Friends presence refresh completed successfully.";
            logger.info("Manual friends presence refresh completed");
            
            return Response.ok(result).build();
            
        } catch (Exception e) {
            logger.error("Error during manual friends presence refresh", e);
            return Response.serverError()
                    .entity("Error during friends presence refresh: " + e.getMessage())
                    .build();
        }
    }

    /**
     * AMÉLIORATION: Retourne le statut des présences pour un utilisateur spécifique
     */
    @GET
    @Path("/user/{userId}")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getUserFriendsPresence(@PathParam("userId") Long userId) {
        try {
            StringBuilder info = new StringBuilder();
            info.append("Friends Presence for User ID: ").append(userId).append("\n");
            info.append("=========================================\n\n");

            List<SocialRelationshipEntity> userFriendships = socialRelationshipDAO.findByUserIdAndStatus(userId, 1L);
            
            if (userFriendships.isEmpty()) {
                info.append("No friends found for this user.\n");
                return Response.ok(info.toString()).build();
            }

            info.append("Friends list:\n");
            for (SocialRelationshipEntity friendship : userFriendships) {
                for (PersonaEntity persona : friendship.getRemoteUser().getPersonas()) {
                    if (persona.getDeletedAt() != null) {
                        info.append("- ").append(persona.getName()).append(" (DELETED)\n");
                        continue;
                    }

                    Long presence = presenceBO.getPresence(persona.getPersonaId());
                    String presenceText = getPresenceText(presence);
                    
                    info.append("- ").append(persona.getName())
                        .append(" (ID: ").append(persona.getPersonaId())
                        .append(") - ").append(presenceText).append("\n");
                }
            }

            logger.info("Friends presence info requested for user {}", userId);
            return Response.ok(info.toString()).build();
            
        } catch (Exception e) {
            logger.error("Error retrieving user friends presence info for user {}", userId, e);
            return Response.serverError()
                    .entity("Error retrieving user friends presence info: " + e.getMessage())
                    .build();
        }
    }

    private String getPresenceText(Long presence) {
        if (presence.equals(PresenceBO.PRESENCE_ONLINE)) {
            return "ONLINE";
        } else if (presence.equals(PresenceBO.PRESENCE_IN_RACE)) {
            return "IN RACE";
        } else {
            return "OFFLINE";
        }
    }
}
