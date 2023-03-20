/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.bo;

import com.soapboxrace.core.dao.CarClassesDAO;
import com.soapboxrace.core.dao.ProductDAO;
import com.soapboxrace.core.dao.CarClassListDAO;
import com.soapboxrace.core.jpa.*;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import java.util.Set;

@Stateless
public class PerformanceBO {

    @EJB
    private CarClassesDAO carClassesDAO;

    @EJB
    private ProductDAO productDAO;

    @EJB
    private CarClassListDAO carClassListDAO;

    public CarClassesEntity calcNewCarClass(CarEntity carEntity) {
        return calcNewCarClass(carEntity, carEntity.getDurability() == 0);
    }

    public CarClassesEntity calcNewCarClass(CarEntity carEntity, boolean ignoreParts) {
        int physicsProfileHash = carEntity.getPhysicsProfileHash();
        CarClassesEntity carClassesEntity = carClassesDAO.findByHash(physicsProfileHash);
        if (carClassesEntity == null) {
            return null;
        }
        int topSpeed = 0;
        int accel = 0;
        int handling = 0;
        if (!ignoreParts) {
            Set<PerformancePartEntity> performanceParts = carEntity.getPerformanceParts();
            for (PerformancePartEntity performancePartEntity : performanceParts) {
                int perfHash = performancePartEntity.getPerformancePartAttribHash();
                ProductEntity productEntity = productDAO.findByHash(perfHash);
                topSpeed = productEntity.getTopSpeed() + topSpeed;
                accel = productEntity.getAccel() + accel;
                handling = productEntity.getHandling() + handling;
            }
        }
        double tt = (topSpeed * 0.0099999998);
        double ta = (accel * 0.0099999998);
        double th = (handling * 0.0099999998);
        double totalChanges = 1 / (((tt + ta + th) * 0.66666669) + 1);
        tt = tt * totalChanges;
        ta = ta * totalChanges;
        th = th * totalChanges;
        double finalConstant = 1 - tt - ta - th;

        double finalTopSpeed1 = carClassesEntity.getTsVar1().doubleValue() * th;
        double finalTopSpeed2 = carClassesEntity.getTsVar2().doubleValue() * ta;
        double finalTopSpeed3 = carClassesEntity.getTsVar3().doubleValue() * tt;
        double finalTopSpeed =
                (finalConstant * carClassesEntity.getTsStock().doubleValue()) + finalTopSpeed1 + finalTopSpeed2
                        + finalTopSpeed3;

        double finalAccel1 = carClassesEntity.getAcVar1().doubleValue() * th;
        double finalAccel2 = carClassesEntity.getAcVar2().doubleValue() * ta;
        double finalAccel3 = carClassesEntity.getAcVar3().doubleValue() * tt;
        double finalAccel = (finalConstant * carClassesEntity.getAcStock().doubleValue()) + finalAccel1 + finalAccel2
                + finalAccel3;

        double finalHandling1 = carClassesEntity.getHaVar1().doubleValue() * th;
        double finalHandling2 = carClassesEntity.getHaVar2().doubleValue() * ta;
        double finalHandling3 = carClassesEntity.getHaVar3().doubleValue() * tt;
        double finalHandling =
                (finalConstant * carClassesEntity.getHaStock().doubleValue()) + finalHandling1 + finalHandling2
                        + finalHandling3;

        double finalClass = ((int) finalTopSpeed + (int) finalAccel + (int) finalHandling) / 3d;
        int finalClassInt = (int) finalClass;

        CarClassListEntity carClassList = carClassListDAO.findByRating(finalClassInt);
        carEntity.setCarClassHash(carClassList.getHash());
        carEntity.setRating(finalClassInt);

        return carClassesEntity;
    }
}