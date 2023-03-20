package com.soapboxrace.core.dao;

import com.soapboxrace.core.dao.util.LongKeyedDAO;
import com.soapboxrace.core.jpa.EventDataSetupEntity;

import javax.ejb.Stateless;
import javax.persistence.TypedQuery;
import java.util.List;

@Stateless
public class EventDataSetupDAO extends LongKeyedDAO<EventDataSetupEntity> {
    public EventDataSetupDAO() {
        super(EventDataSetupEntity.class);
    }

    public EventDataSetupEntity findByHash(String hash) {
        TypedQuery<EventDataSetupEntity> query = entityManager.createNamedQuery("EventDataSetupEntity.findByHash", EventDataSetupEntity.class);
        query.setParameter("hash", hash);
        List<EventDataSetupEntity> resultList = query.getResultList();

        if (resultList == null || resultList.isEmpty()) {
            return null;
        }

        return resultList.get(0);
    }
}
