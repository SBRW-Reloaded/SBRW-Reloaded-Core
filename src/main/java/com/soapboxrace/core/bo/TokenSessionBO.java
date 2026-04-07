/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.bo;

import com.soapboxrace.core.auth.AuthException;
import com.soapboxrace.core.auth.AuthResultVO;
import com.soapboxrace.core.auth.BanInfoVO;
import com.soapboxrace.core.auth.verifiers.PasswordVerifier;
import com.soapboxrace.core.dao.UserDAO;
import com.soapboxrace.core.engine.EngineException;
import com.soapboxrace.core.engine.EngineExceptionCode;
import com.soapboxrace.core.jpa.BanEntity;
import com.soapboxrace.core.jpa.PersonaEntity;
import com.soapboxrace.core.jpa.TokenSessionEntity;
import com.soapboxrace.core.jpa.UserEntity;

import javax.ejb.Schedule;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.inject.Inject;
import javax.enterprise.context.ApplicationScoped;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.NotAuthorizedException;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Startup
@Singleton
public class TokenSessionBO {

    @Inject
    private UserDAO userDAO;

    @Inject
    private ParameterBO parameterBO;

    @Inject
    private AuthenticationBO authenticationBO;

    @Inject
    private HardwareInfoBO hardwareInfoBO;

    @Inject
    private PresenceBO presenceBO;

    @Inject
    private LobbyBO lobbyBO;

    private final Map<String, TokenSessionEntity> sessionKeyToTokenMap = new ConcurrentHashMap<>();
    private final Map<Long, String> userIdToSessionKeyMap = new ConcurrentHashMap<>();

    public String createToken(UserEntity userEntity, String clientHostName) {
        Date expirationDate = getMinutes(parameterBO.getIntParam("SESSION_LENGTH_MINUTES", 130));
        String randomUUID = UUID.randomUUID().toString();
        TokenSessionEntity tokenSessionEntity = new TokenSessionEntity();

        tokenSessionEntity.setExpirationDate(expirationDate);
        tokenSessionEntity.setSecurityToken(randomUUID);
        tokenSessionEntity.setUserEntity(userEntity);
        tokenSessionEntity.setPremium(userEntity.isPremium());
        tokenSessionEntity.setClientHostIp(clientHostName);
        tokenSessionEntity.setActivePersonaId(0L);
        tokenSessionEntity.setEventSessionId(null);
        tokenSessionEntity.setLastHeartbeatTime(System.currentTimeMillis());

        for (PersonaEntity personaEntity : userEntity.getPersonas()) {
            tokenSessionEntity.getAllowedPersonaIds().add(personaEntity.getPersonaId());
        }

        this.sessionKeyToTokenMap.put(randomUUID, tokenSessionEntity);
        this.userIdToSessionKeyMap.put(userEntity.getId(), randomUUID);

        return randomUUID;
    }

    public TokenSessionEntity validateToken(Long userId, String securityToken) {
        TokenSessionEntity tokenSessionEntity = sessionKeyToTokenMap.get(securityToken);
        if (tokenSessionEntity == null || !tokenSessionEntity.getUserEntity().getId().equals(userId)) {
            throw new NotAuthorizedException("Invalid Token");
        }
        long time = new Date().getTime();
        long tokenTime = tokenSessionEntity.getExpirationDate().getTime();
        if (time > tokenTime) {
            deleteByUserId(userId);
            throw new NotAuthorizedException("Expired Token as of " + tokenSessionEntity.getExpirationDate().toString());
        }

        return tokenSessionEntity;
    }

    public TokenSessionEntity findByUserId(Long userId) {
        String sessionKey = this.userIdToSessionKeyMap.get(userId);

        if (sessionKey != null) {
            return Objects.requireNonNull(this.sessionKeyToTokenMap.get(sessionKey), () -> String.format("User %d has session key, but session is missing!", userId));
        }

        return null;
    }

    public void removeSession(String sessionKey) {
        if (sessionKey != null) {	
            this.sessionKeyToTokenMap.remove(sessionKey);	
        }
    }


    public void deleteByUserId(Long userId) {
        String sessionKey = this.userIdToSessionKeyMap.remove(userId);
        if (sessionKey != null) {
            // CRITICAL FIX: Nettoyer les lobbies actifs avant de supprimer la session
            // Cela évite les lobbies fantômes si un joueur crash/quitte sans décliner
            TokenSessionEntity tokenSession = this.sessionKeyToTokenMap.get(sessionKey);
            if (tokenSession != null && tokenSession.getAllowedPersonaIds() != null) {
                // Retirer tous les personas de ce compte de leurs lobbies actifs
                for (Long personaId : tokenSession.getAllowedPersonaIds()) {
                    try {
                        lobbyBO.removePersonaFromAllActiveLobbies(personaId);
                    } catch (Exception e) {
                        // Ne pas bloquer la déconnexion si le nettoyage échoue
                        // Log en debug seulement pour éviter de spammer les logs
                    }
                }
            }
            
            removeSession(sessionKey);

            //and delete status for this persona in db:
            userDAO.updateUserState(userId, "OFFLINE");
        }
    }

    public AuthResultVO login(String email, PasswordVerifier password, HttpServletRequest httpRequest) throws AuthException {
        if (email == null || email.isEmpty()) {
            throw new AuthException("Invalid email or password");
        }

        UserEntity userEntity = userDAO.findByEmailWithFreshData(email);
        if (userEntity == null) {
            throw new AuthException("Invalid email or password");
        }
        if (!password.verifyHash(userEntity)) {
            throw new AuthException("Invalid email or password");
        }

        // Vérifier le mode maintenance
        Boolean isMaintenanceMode = parameterBO.getBoolParam("IS_MAINTENANCE");
        if (isMaintenanceMode != null && isMaintenanceMode.booleanValue()) {
            if (!userEntity.isAdmin()) {
                throw new AuthException("Server is currently under maintenance. Only administrators can connect.");
            }
        }

        if (userEntity.isLocked()) {
            // Vérifier si c'est un nouveau compte (jamais connecté) ou un compte existant verrouillé
            if (userEntity.getLastLogin() == null) {
                throw new AuthException("Account not activated. Please check your email inbox (including spam folder) to activate your account before playing.");
            } else {
                throw new AuthException("Account locked. Contact the moderation team via our Discord server for more information.");
            }
        }

        BanEntity banEntity = authenticationBO.checkUserBan(userEntity);

        if (banEntity != null) {
            BanInfoVO banInfoVO = new BanInfoVO(banEntity.getReason(), banEntity.getEndsAt());
            throw new AuthException(banInfoVO);
        }

        Long userId = userEntity.getId();
        userDAO.updateLastLogin(userId, LocalDateTime.now());
        // DON'T modify the managed entity - it would trigger automatic save with default values!
        deleteByUserId(userId);
        String randomUUID = createToken(userEntity, httpRequest.getRemoteHost());

        return new AuthResultVO(userId, randomUUID);
    }

    public void verifyPersonaOwnership(TokenSessionEntity tokenSessionEntity, Long personaId) {
        Objects.requireNonNull(tokenSessionEntity);

        if (!tokenSessionEntity.getAllowedPersonaIds().contains(personaId)) {
            throw new EngineException(EngineExceptionCode.RemotePersonaDoesNotBelongToUser, true);
        }
    }

    public void setActivePersonaId(TokenSessionEntity tokenSessionEntity, Long personaId) {
        Objects.requireNonNull(tokenSessionEntity);

        if (!personaId.equals(0L)) {
            verifyPersonaOwnership(tokenSessionEntity, personaId);
        }

        tokenSessionEntity.setActivePersonaId(personaId);
    }

    public void setActiveLobbyId(TokenSessionEntity tokenSessionEntity, Long lobbyId) {
        Objects.requireNonNull(tokenSessionEntity);
        tokenSessionEntity.setActiveLobbyId(lobbyId);
    }

    public void setEventSessionId(TokenSessionEntity tokenSessionEntity, Long eventSessionId) {
        Objects.requireNonNull(tokenSessionEntity);
        tokenSessionEntity.setEventSessionId(eventSessionId);
    }

    public void setRelayCryptoTicket(TokenSessionEntity tokenSessionEntity, String relayCryptoTicket) {
        Objects.requireNonNull(tokenSessionEntity);
        tokenSessionEntity.setRelayCryptoTicket(relayCryptoTicket);
    }

    private Date getMinutes(int minutes) {
        long time = new Date().getTime();
        time = time + (minutes * 60000L);
        return new Date(time);
    }

    /**
     * Records a heartbeat for the given security token.
     * This updates the lastHeartbeatTime to the current timestamp.
     * 
     * @param securityToken The security token for the session
     */
    public void recordHeartbeat(String securityToken) {
        if (securityToken != null) {
            TokenSessionEntity session = this.sessionKeyToTokenMap.get(securityToken);
            if (session != null) {
                session.setLastHeartbeatTime(System.currentTimeMillis());
            }
        }
    }

    /**
     * Checks if a player is currently in an event (race or viewing rewards screen).
     * Used to determine if lobby invitations should be blocked.
     * 
     * @param userId The user ID to check
     * @return true if the player is currently in an event/rewards screen
     */
    public boolean isPlayerInEvent(Long userId) {
        TokenSessionEntity session = findByUserId(userId);
        if (session != null) {
            Long eventSessionId = session.getEventSessionId();
            return eventSessionId != null && !eventSessionId.equals(0L);
        }
        return false;
    }

    /**
     * Expires sessions that haven't received a heartbeat in the configured time.
     * - Sessions with active persona: expire after 3 minutes without heartbeat (default)
     * - Sessions without active persona: expire after 10 minutes without heartbeat (default)
     * 
     * When a session expires, the user's presence is set to OFFLINE.
     * This task runs every minute.
     */
    @Schedule(minute = "*/1", hour = "*", persistent = false)
    public void expireInactiveSessions() {
        long currentTime = System.currentTimeMillis();
        int activePersonaTimeout = parameterBO.getIntParam("SESSION_HEARTBEAT_TIMEOUT_ACTIVE", 3) * 60 * 1000; // minutes to ms
        int inactivePersonaTimeout = parameterBO.getIntParam("SESSION_HEARTBEAT_TIMEOUT_INACTIVE", 10) * 60 * 1000; // minutes to ms
        
        int expiredCount = 0;
        int expiredInRaceCount = 0;
        
        for (Map.Entry<String, TokenSessionEntity> entry : this.sessionKeyToTokenMap.entrySet()) {
            TokenSessionEntity session = entry.getValue();
            Long lastHeartbeat = session.getLastHeartbeatTime();
            
            if (lastHeartbeat == null) {
                // No heartbeat recorded, skip expiration (shouldn't happen after this fix)
                continue;
            }
            
            long timeSinceLastHeartbeat = currentTime - lastHeartbeat;
            boolean hasActivePersona = session.getActivePersonaId() != null && !session.getActivePersonaId().equals(0L);
            int timeoutThreshold = hasActivePersona ? activePersonaTimeout : inactivePersonaTimeout;
            
            if (timeSinceLastHeartbeat > timeoutThreshold) {
                // Session has expired due to no heartbeat
                Long userId = session.getUserEntity().getId();
                Long activePersonaId = session.getActivePersonaId();
                
                // Vérifier si le joueur était en course lors de la déconnexion
                boolean wasInRace = false;
                if (activePersonaId != null && !activePersonaId.equals(0L)) {
                    wasInRace = presenceBO.isPlayerInRace(activePersonaId);
                    if (wasInRace) {
                        expiredInRaceCount++;
                    }
                }
                
                System.out.println(String.format(
                    "[TokenSessionBO] Expiring session for user %d (persona %d) - no heartbeat for %d ms (threshold: %d ms) - wasInRace: %s",
                    userId, activePersonaId, timeSinceLastHeartbeat, timeoutThreshold, wasInRace
                ));
                
                // Update presence to offline before deleting session
                // Force la transition même si le joueur était en course
                if (activePersonaId != null && !activePersonaId.equals(0L)) {
                    presenceBO.forcePresenceOffline(activePersonaId);
                }
                
                // Delete the session
                deleteByUserId(userId);
                expiredCount++;
            }
        }
        
        if (expiredCount > 0) {
            System.out.println(String.format("[TokenSessionBO] Expired %d inactive sessions (%d were in race)", 
                                            expiredCount, expiredInRaceCount));
        }
    }
}