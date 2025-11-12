/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.dao;

import com.soapboxrace.core.dao.util.LongKeyedDAO;
import com.soapboxrace.core.jpa.UserEntity;

import javax.ejb.Stateless;
import javax.persistence.TypedQuery;
import java.util.logging.Logger;
import java.util.List;

@Stateless
public class UserDAO extends LongKeyedDAO<UserEntity> {

    private static final Logger LOGGER = Logger.getLogger(UserDAO.class.getName());

    public UserDAO() {
        super(UserEntity.class);
    }
    
    @Override
    public void update(UserEntity userEntity) {
        super.update(userEntity);
    }

    public UserEntity findByEmail(String email) {
        TypedQuery<UserEntity> query = entityManager.createNamedQuery("UserEntity.findByEmail", UserEntity.class);
        query.setParameter("email", email);

        List<UserEntity> resultList = query.getResultList();
        return !resultList.isEmpty() ? resultList.get(0) : null;
    }

    public UserEntity findByIP(String ip) {
        TypedQuery<UserEntity> query = entityManager.createNamedQuery("UserEntity.findByIpAddress", UserEntity.class);
        query.setParameter("ipAddress", ip);

        List<UserEntity> resultList = query.getResultList();
        return !resultList.isEmpty() ? resultList.get(0) : null;
    }

    public int countUsersByIpAddress(String ip) {
        TypedQuery<Long> query = entityManager.createNamedQuery("UserEntity.countUsersByIpAddress", Long.class);
        query.setParameter("ipAddress", ip);
        return query.getSingleResult().intValue();
    }

    public Long countUsers() {
        return entityManager.createNamedQuery("UserEntity.countUsers", Long.class).getSingleResult();
    }
    
    public void updateLastLogin(Long userId, java.time.LocalDateTime lastLogin) {
        entityManager.createQuery("UPDATE UserEntity SET lastLogin = :lastLogin WHERE id = :userId")
                .setParameter("lastLogin", lastLogin)
                .setParameter("userId", userId)
                .executeUpdate();
    }
    
    public void updateUserState(Long userId, String state) {
        entityManager.createQuery("UPDATE UserEntity SET state = :state WHERE id = :userId")
                .setParameter("state", state)
                .setParameter("userId", userId)
                .executeUpdate();
    }
    
    public void updateHwid(Long userId, String hwid) {
        entityManager.createQuery("UPDATE UserEntity SET hwid = :hwid WHERE id = :userId")
                .setParameter("hwid", hwid)
                .setParameter("userId", userId)
                .executeUpdate();
    }
    
    public void updateSelectedPersonaIndex(Long userId, int selectedPersonaIndex) {
        entityManager.createQuery("UPDATE UserEntity SET selectedPersonaIndex = :selectedPersonaIndex WHERE id = :userId")
                .setParameter("selectedPersonaIndex", selectedPersonaIndex)
                .setParameter("userId", userId)
                .executeUpdate();
    }
    
    public void updateGameHardwareHashAndState(Long userId, String gameHardwareHash, String state) {
        entityManager.createQuery("UPDATE UserEntity SET gameHardwareHash = :gameHardwareHash, state = :state WHERE id = :userId")
                .setParameter("gameHardwareHash", gameHardwareHash)
                .setParameter("state", state)
                .setParameter("userId", userId)
                .executeUpdate();
    }
    
    public void updateLocked(Long userId, boolean locked) {
        entityManager.createQuery("UPDATE UserEntity SET locked = :locked WHERE id = :userId")
                .setParameter("locked", locked)
                .setParameter("userId", userId)
                .executeUpdate();
    }
    
    public void updateSocialSettings(Long userId, boolean appearOffline, int declineGroupInvite, 
                                   boolean declineIncommingFriendRequests, int declinePrivateInvite,
                                   boolean hideOfflineFriends, boolean showNewsOnSignIn,
                                   boolean showOnlyPlayersInSameChatChannel) {
        entityManager.createQuery("UPDATE UserEntity SET " +
                "appearOffline = :appearOffline, " +
                "declineGroupInvite = :declineGroupInvite, " +
                "declineIncommingFriendRequests = :declineIncommingFriendRequests, " +
                "declinePrivateInvite = :declinePrivateInvite, " +
                "hideOfflineFriends = :hideOfflineFriends, " +
                "showNewsOnSignIn = :showNewsOnSignIn, " +
                "showOnlyPlayersInSameChatChannel = :showOnlyPlayersInSameChatChannel " +
                "WHERE id = :userId")
                .setParameter("appearOffline", appearOffline)
                .setParameter("declineGroupInvite", declineGroupInvite)
                .setParameter("declineIncommingFriendRequests", declineIncommingFriendRequests)
                .setParameter("declinePrivateInvite", declinePrivateInvite)
                .setParameter("hideOfflineFriends", hideOfflineFriends)
                .setParameter("showNewsOnSignIn", showNewsOnSignIn)
                .setParameter("showOnlyPlayersInSameChatChannel", showOnlyPlayersInSameChatChannel)
                .setParameter("userId", userId)
                .executeUpdate();
    }    public void updateMaxCarSlots(Long userId, int maxCarSlots) {
        entityManager.createQuery("UPDATE UserEntity SET maxCarSlots = :maxCarSlots WHERE id = :userId")
                .setParameter("maxCarSlots", maxCarSlots)
                .setParameter("userId", userId)
                .executeUpdate();
    }
    
    /**
     * Find user by email and return a DETACHED entity to avoid automatic saves
     * This prevents Hibernate from automatically saving default values
     */
    public UserEntity findByEmailWithFreshData(String email) {
        TypedQuery<UserEntity> query = entityManager.createNamedQuery("UserEntity.findByEmail", UserEntity.class);
        query.setParameter("email", email);
        try {
            UserEntity user = query.getSingleResult();
            // DETACH the entity so it won't be automatically saved by Hibernate
            entityManager.detach(user);
            return user;
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Find user by ID and return a DETACHED entity to avoid automatic saves
     * This prevents Hibernate from automatically saving default values
     */
    public UserEntity findDetached(Long userId) {
        UserEntity user = find(userId);
        if (user != null) {
            // DETACH the entity so it won't be automatically saved by Hibernate
            entityManager.detach(user);
        }
        return user;
    }
}
