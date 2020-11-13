/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.dao;

import com.soapboxrace.core.dao.util.CacheableDAO;
import com.soapboxrace.core.jpa.PersonaEntity;

import javax.ejb.Lock;
import javax.ejb.LockType;
import javax.ejb.Singleton;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import java.util.List;

@Singleton
@Lock(LockType.READ)
public class PersonaDAO extends CacheableDAO<PersonaEntity, Long> {

    public PersonaDAO() {
        super(PersonaEntity.class);
    }

    public PersonaEntity findByName(String name) {
        TypedQuery<PersonaEntity> query = entityManager.createNamedQuery("PersonaEntity.findByName",
                PersonaEntity.class);
        query.setParameter("name", name);

        List<PersonaEntity> resultList = query.getResultList();
        return !resultList.isEmpty() ? resultList.get(0) : null;
    }

    public void addPointsToScore(Long personaId, Integer points) {
        Query query = entityManager.createNamedQuery("PersonaEntity.addPointsToScore");
        query.setParameter("personaId", personaId);
        query.setParameter("points", points);
        query.executeUpdate();
    }

    public Long countPersonas() {
        CriteriaBuilder qb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> cq = qb.createQuery(Long.class);
        cq.select(qb.count(cq.from(PersonaEntity.class)));
        return entityManager.createQuery(cq).getSingleResult();
    }
}