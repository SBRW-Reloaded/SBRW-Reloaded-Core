package com.soapboxrace.core.dao;

import com.soapboxrace.core.dao.util.LongKeyedDAO;
import com.soapboxrace.core.jpa.UsedPowerupEntity;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;

@ApplicationScoped

@Transactional
public class UsedPowerupDAO extends LongKeyedDAO<UsedPowerupEntity> {

    public UsedPowerupDAO() {
        super(UsedPowerupEntity.class);
    }
}
