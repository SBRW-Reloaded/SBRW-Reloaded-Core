package com.soapboxrace.core.bo;

import com.soapboxrace.core.dao.UserDAO;
import com.soapboxrace.core.jpa.TokenSessionEntity;
import com.soapboxrace.core.jpa.UserEntity;

import javax.ejb.EJB;
import javax.enterprise.context.RequestScoped;

@RequestScoped
public class RequestSessionInfo {

    @EJB
    private UserDAO userDAO;

    private TokenSessionEntity tokenSessionEntity;

    public TokenSessionEntity getTokenSessionEntity() {
        return tokenSessionEntity;
    }

    public void setTokenSessionEntity(TokenSessionEntity tokenSessionEntity) {
        this.tokenSessionEntity = tokenSessionEntity;
    }

    public UserEntity getUser() {
        // Always get fresh user data from database to avoid stale cached values
        // Use detached entity to prevent automatic saves with default values
        UserEntity cachedUser = tokenSessionEntity.getUserEntity();
        return userDAO.findDetached(cachedUser.getId());
    }

    public boolean isAdmin() {
        return getUser().isAdmin();
    }

    public Long getEventSessionId() {
        return tokenSessionEntity.getEventSessionId();
    }

    public Long getActiveLobbyId() {
        return tokenSessionEntity.getActiveLobbyId();
    }

    public Long getActivePersonaId() {
        return tokenSessionEntity.getActivePersonaId();
    }

    public String getRelayCryptoTicket() {
        return tokenSessionEntity.getRelayCryptoTicket();
    }
}
