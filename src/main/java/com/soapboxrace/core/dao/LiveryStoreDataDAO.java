package com.soapboxrace.core.dao;

import com.soapboxrace.core.dao.util.LongKeyedDAO;
import com.soapboxrace.core.jpa.LiveryStoreDataEntity;

import javax.ejb.Stateless;
import java.util.List;
import javax.persistence.TypedQuery;

@Stateless
public class LiveryStoreDataDAO extends LongKeyedDAO<LiveryStoreDataEntity> {

    public LiveryStoreDataDAO() {
        super(LiveryStoreDataEntity.class);
    }

    public List<LiveryStoreDataEntity> getVinylsByCode(String liverycode) {
        TypedQuery<LiveryStoreDataEntity> query = entityManager.createNamedQuery("LiveryStoreDataEntity.getVinylsByCode", LiveryStoreDataEntity.class);
        query.setParameter("liverycode", liverycode);
        return query.getResultList();
    }
}
