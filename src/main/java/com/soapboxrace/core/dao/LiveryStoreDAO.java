package com.soapboxrace.core.dao;

import com.soapboxrace.core.dao.util.LongKeyedDAO;
import com.soapboxrace.core.jpa.LiveryStoreEntity;

import javax.ejb.Stateless;
import javax.persistence.TypedQuery;
import java.util.List;

@Stateless
public class LiveryStoreDAO extends LongKeyedDAO<LiveryStoreEntity> {

    public LiveryStoreDAO() {
        super(LiveryStoreEntity.class);
    }

    public LiveryStoreEntity findLiveryByCode(String code) {
        TypedQuery<LiveryStoreEntity> query = entityManager.createNamedQuery("LiveryStoreEntity.findLiveryByCode", LiveryStoreEntity.class);
        query.setParameter("code", code);

        List<LiveryStoreEntity> resultList = query.getResultList();
        return !resultList.isEmpty() ? resultList.get(0) : null;
    }
}
