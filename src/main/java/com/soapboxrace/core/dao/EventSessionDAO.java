/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.dao;

import com.soapboxrace.core.dao.util.LongKeyedDAO;
import com.soapboxrace.core.jpa.EventSessionEntity;

import javax.ejb.Stateless;
import javax.persistence.LockModeType;

@Stateless
public class EventSessionDAO extends LongKeyedDAO<EventSessionEntity> {

    public EventSessionDAO() {
        super(EventSessionEntity.class);
    }
    
    /**
     * Find EventSession with PESSIMISTIC_WRITE lock to prevent race conditions
     * when multiple threads try to create Race Again lobby for the same session.
     */
    public EventSessionEntity findWithLock(Long id) {
        return this.entityManager.find(EventSessionEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
    }
    
    /**
     * Update EventSession and immediately flush to database.
     * This ensures the changes are visible to other threads waiting on PESSIMISTIC_WRITE lock.
     */
    public void updateAndFlush(EventSessionEntity entity) {
        this.entityManager.merge(entity);
        this.entityManager.flush();
    }
}
