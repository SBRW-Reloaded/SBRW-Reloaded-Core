/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.dao;

import com.soapboxrace.core.dao.util.StringKeyedDAO;
import com.soapboxrace.core.jpa.CarClassesEntity;

import javax.ejb.Stateless;
import javax.persistence.TypedQuery;

@Stateless
public class CarClassesDAO extends StringKeyedDAO<CarClassesEntity> {

    public CarClassesDAO() {
        super(CarClassesEntity.class);
    }

    public CarClassesEntity findByHash(int hash) {
        TypedQuery<CarClassesEntity> query = entityManager.createNamedQuery("CarClassesEntity.findByHash", CarClassesEntity.class);
        query.setParameter("hash", hash);
        try {
            return query.getSingleResult();
        } catch (Exception e) { }
        return null;
    }

    public CarClassesEntity findByName(String name) {
        TypedQuery<CarClassesEntity> query = entityManager.createNamedQuery("CarClassesEntity.findByName", CarClassesEntity.class);
        query.setParameter("name", name);
        try {
            return query.getSingleResult();
        } catch (Exception e) { }
        return null;    
    }
}