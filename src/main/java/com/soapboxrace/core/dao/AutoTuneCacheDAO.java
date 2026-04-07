package com.soapboxrace.core.dao;

import com.soapboxrace.core.dao.util.LongKeyedDAO;
import com.soapboxrace.core.jpa.AutoTuneCacheEntity;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import javax.persistence.TypedQuery;
import java.util.List;

@ApplicationScoped
@Transactional
public class AutoTuneCacheDAO extends LongKeyedDAO<AutoTuneCacheEntity> {

    public AutoTuneCacheDAO() {
        super(AutoTuneCacheEntity.class);
    }

    public List<AutoTuneCacheEntity> findAll() {
        TypedQuery<AutoTuneCacheEntity> query = entityManager.createNamedQuery("AutoTuneCacheEntity.findAll", AutoTuneCacheEntity.class);
        return query.getResultList();
    }

    @SuppressWarnings("unchecked")
    public List<Object[]> findAllKeys() {
        return entityManager.createNamedQuery("AutoTuneCacheEntity.findAllKeys").getResultList();
    }

    public AutoTuneCacheEntity findByKey(int physicsHash, String className, String priority, int level) {
        TypedQuery<AutoTuneCacheEntity> query = entityManager.createNamedQuery("AutoTuneCacheEntity.findByKey", AutoTuneCacheEntity.class);
        query.setParameter("physicsHash", physicsHash);
        query.setParameter("className", className);
        query.setParameter("priority", priority);
        query.setParameter("level", level);
        List<AutoTuneCacheEntity> results = query.getResultList();
        return results.isEmpty() ? null : results.get(0);
    }

    public AutoTuneCacheEntity findBestForLevel(int physicsHash, String className, String priority, int level) {
        TypedQuery<AutoTuneCacheEntity> query = entityManager.createNamedQuery("AutoTuneCacheEntity.findBestForLevel", AutoTuneCacheEntity.class);
        query.setParameter("physicsHash", physicsHash);
        query.setParameter("className", className);
        query.setParameter("priority", priority);
        query.setParameter("level", level);
        query.setMaxResults(1);
        List<AutoTuneCacheEntity> results = query.getResultList();
        return results.isEmpty() ? null : results.get(0);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void insertNewTx(AutoTuneCacheEntity entity) {
        entityManager.persist(entity);
        entityManager.flush();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void deleteByPhysicsHash(int physicsHash) {
        entityManager.createNamedQuery("AutoTuneCacheEntity.deleteByPhysicsHash")
                .setParameter("physicsHash", physicsHash)
                .executeUpdate();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void deleteAll() {
        entityManager.createNamedQuery("AutoTuneCacheEntity.deleteAll").executeUpdate();
    }
}
