package com.soapboxrace.core.dao;

import com.soapboxrace.core.dao.util.LongKeyedDAO;
import com.soapboxrace.core.jpa.RankedEntity;

import javax.ejb.Stateless;

@Stateless
public class RankedDAO extends LongKeyedDAO<RankedEntity> {
    public RankedDAO() {
        super(RankedEntity.class);
    }
}