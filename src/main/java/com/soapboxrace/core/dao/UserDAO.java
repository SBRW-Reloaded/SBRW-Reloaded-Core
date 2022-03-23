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
import javax.persistence.Query;
import java.util.List;

@Stateless
public class UserDAO extends LongKeyedDAO<UserEntity> {

    public UserDAO() {
        super(UserEntity.class);
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

    public void updateOnlineState(String state) {
        this.entityManager.createNamedQuery("UserEntity.updateOnlineState").setParameter("state", state).executeUpdate();
    }
}
