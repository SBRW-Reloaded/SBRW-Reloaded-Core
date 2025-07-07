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

import javax.ejb.EJB;
import javax.ejb.Lock;
import javax.ejb.LockType;
import javax.ejb.Singleton;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.NotAuthorizedException;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
@Lock(LockType.READ)
public class TokenSessionBO {

    @EJB
    private UserDAO userDAO;

    @EJB
    private ParameterBO parameterBO;

    @EJB
    private AuthenticationBO authenticationBO;

    @EJB
    private HardwareInfoBO hardwareInfoBO;

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
            removeSession(sessionKey);

            //and delete status for this persona in db:
            UserEntity userEntity = userDAO.find(userId);
            if(userEntity != null) {
                userEntity.setState("OFFLINE");
                userDAO.update(userEntity);
            }
        }
    }

    public AuthResultVO login(String email, PasswordVerifier password, HttpServletRequest httpRequest) throws AuthException {
        if (email == null || email.isEmpty()) {
            throw new AuthException("Invalid email or password");
        }

        UserEntity userEntity = userDAO.findByEmail(email);
        if (userEntity == null) {
            throw new AuthException("Invalid email or password");
        }
        if (!password.verifyHash(userEntity)) {
            throw new AuthException("Invalid email or password");
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

        userEntity.setLastLogin(LocalDateTime.now());
        userDAO.update(userEntity);
        Long userId = userEntity.getId();
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
}