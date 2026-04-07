/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.dao;

import com.soapboxrace.core.dao.util.StringKeyedDAO;
import com.soapboxrace.core.jpa.CarClassListEntity;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import javax.persistence.TypedQuery;
import java.util.List;

@ApplicationScoped

@Transactional
public class CarClassListDAO extends StringKeyedDAO<CarClassListEntity> {

    public CarClassListDAO() {
        super(CarClassListEntity.class);
    }

    public List<CarClassListEntity> findAll() {
        TypedQuery<CarClassListEntity> query = this.entityManager.createNamedQuery("CarClassListEntity.findAll", CarClassListEntity.class);
        return query.getResultList();
    }

    public CarClassListEntity findByHash(int hash) {
        TypedQuery<CarClassListEntity> query = entityManager.createNamedQuery("CarClassListEntity.findByHash", CarClassListEntity.class);
        query.setParameter("hash", hash);
        List<CarClassListEntity> resultList = query.getResultList();
        return resultList.isEmpty() ? null : resultList.get(0);
    }

    public CarClassListEntity findByRating(int rating) {
        TypedQuery<CarClassListEntity> query = entityManager.createNamedQuery("CarClassListEntity.findByRating", CarClassListEntity.class);
        query.setParameter("rating", rating);
        return query.getSingleResult();
    }

    public CarClassListEntity findByName(String name) {
        TypedQuery<CarClassListEntity> query = entityManager.createNamedQuery("CarClassListEntity.findByName", CarClassListEntity.class);
        query.setParameter("name", name.toUpperCase());
        List<CarClassListEntity> results = query.getResultList();
        return results.isEmpty() ? null : results.get(0);
    }
}