/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.dao;

import com.soapboxrace.core.dao.util.StringKeyedDAO;
import com.soapboxrace.core.jpa.CarClassListEntity;

import javax.ejb.Stateless;
import javax.persistence.TypedQuery;
import java.util.List;

@Stateless
public class CarClassListDAO extends StringKeyedDAO<CarClassListEntity> {

    public CarClassListDAO() {
        super(CarClassListEntity.class);
    }

    public List<CarClassListEntity> findAll() {
        TypedQuery<CarClassListEntity> query = this.entityManager.createNamedQuery("CarClassListEntity.findAll", CarClassListEntity.class);
        return query.getResultList();
    }

    public CarClassListEntity findByRating(int rating) {
        TypedQuery<CarClassListEntity> query = entityManager.createNamedQuery("CarClassListEntity.findByRating", CarClassListEntity.class);
        query.setParameter("rating", rating);
        return query.getSingleResult();
    }
}