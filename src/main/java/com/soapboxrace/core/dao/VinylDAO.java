package com.soapboxrace.core.dao;

import com.soapboxrace.core.dao.util.LongKeyedDAO;
import com.soapboxrace.core.jpa.CarEntity;
import com.soapboxrace.core.jpa.VinylEntity;

import javax.ejb.Stateless;
import java.util.List;
import javax.persistence.TypedQuery;
import javax.persistence.Query;

@Stateless
public class VinylDAO extends LongKeyedDAO<VinylEntity> {
    public VinylDAO() {
        super(VinylEntity.class);
    }

    public VinylEntity findByCarId(Long id) {
        TypedQuery<VinylEntity> query = entityManager.createNamedQuery("VinylEntity.findByCarId", VinylEntity.class);
        query.setParameter("carid", id);

        List<VinylEntity> resultList = query.getResultList();
        return !resultList.isEmpty() ? resultList.get(0) : null;
    }

    public void deleteByCar(CarEntity carEntity) {
        Query query = entityManager.createNamedQuery("VinylEntity.deleteByCar");
        query.setParameter("customCar", carEntity);
        query.executeUpdate();
    }
}
