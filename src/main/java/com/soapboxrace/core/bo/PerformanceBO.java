/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.bo;

import com.google.common.collect.ImmutableRangeMap;
import com.google.common.collect.Range;
import com.google.common.collect.RangeMap;
import com.soapboxrace.core.dao.CarClassDAO;
import com.soapboxrace.core.dao.CarStatsDAO;
import com.soapboxrace.core.dao.ProductDAO;
import com.soapboxrace.core.jpa.*;
import org.slf4j.Logger;

import javax.annotation.PostConstruct;
import javax.ejb.EJB;
import javax.ejb.Lock;
import javax.ejb.LockType;
import javax.ejb.Singleton;
import javax.inject.Inject;
import java.util.List;

@Singleton
@Lock(LockType.READ)
public class PerformanceBO {

    @EJB
    private CarClassDAO carClassDAO;

    @EJB
    private CarStatsDAO carStatsDAO;

    @EJB
    private ProductDAO productDAO;

    @Inject
    private Logger logger;

    /**
     * {@link RangeMap} is marked as Beta, but it's been that way for quite some time.
     * It's probably safe to use - certainly the most convenient.
     */
    @SuppressWarnings("UnstableApiUsage")
    private RangeMap<Integer, Integer> carClassRanges;

    private List<CarClassEntity> carClassEntities;

    @PostConstruct
    @SuppressWarnings("UnstableApiUsage")
    public void init() {
        // Load all car class definitions
        this.carClassEntities = carClassDAO.findAll();

        // We can use a range-based map to facilitate fast rating->class lookups.
        // This eliminates any possible need to repeatedly poll the database.
        ImmutableRangeMap.Builder<Integer, Integer> mapBuilder = ImmutableRangeMap.builder();

        for (CarClassEntity carClassEntity : carClassEntities) {
            // Create an entry over the closed range
            //      [minRating, maxRating]
            // that maps to the class hash.
            mapBuilder.put(Range.closed(carClassEntity.getMinRating(), carClassEntity.getMaxRating()),
                    carClassEntity.getHash());
        }

        // Build the map and save it for future use
        this.carClassRanges = mapBuilder.build();
    }

    public void calcNewCarClass(CustomCarEntity customCarEntity) {
        calcNewCarClass(customCarEntity, customCarEntity.getOwnedCar().getDurability() == 0);
    }

    @SuppressWarnings("UnstableApiUsage")
    public void calcNewCarClass(CustomCarEntity customCarEntity, boolean ignoreParts) {
        int physicsProfileHash = customCarEntity.getPhysicsProfileHash();
        CarStatsEntity carStatsEntity = carStatsDAO.find(physicsProfileHash);
        if (carStatsEntity == null) {
            return;
        }
        int topSpeed = 0;
        int accel = 0;
        int handling = 0;
        if (!ignoreParts) {
            List<PerformancePartEntity> performanceParts = customCarEntity.getPerformanceParts();
            for (PerformancePartEntity performancePartEntity : performanceParts) {
                int perfHash = performancePartEntity.getPerformancePartAttribHash();
                ProductEntity productEntity = productDAO.findByHash(perfHash);
                topSpeed = productEntity.getTopSpeed() + topSpeed;
                accel = productEntity.getAccel() + accel;
                handling = productEntity.getHandling() + handling;
            }
        }
        float tt = (float) (topSpeed * 0.01);
        float ta = (float) (accel * 0.01);
        float th = (float) (handling * 0.01);
        float totalChanges = 1 / (((tt + ta + th) * 0.666666666666666f) + 1f);
        tt = tt * totalChanges;
        ta = ta * totalChanges;
        th = th * totalChanges;
        float finalConstant = 1 - tt - ta - th;

        float finalTopSpeed1 = carStatsEntity.getTsVar1().floatValue() * th;
        float finalTopSpeed2 = carStatsEntity.getTsVar2().floatValue() * ta;
        float finalTopSpeed3 = carStatsEntity.getTsVar3().floatValue() * tt;
        float finalTopSpeed =
                (finalConstant * carStatsEntity.getTsStock().floatValue()) + finalTopSpeed1 + finalTopSpeed2
                        + finalTopSpeed3;

        float finalAccel1 = carStatsEntity.getAcVar1().floatValue() * th;
        float finalAccel2 = carStatsEntity.getAcVar2().floatValue() * ta;
        float finalAccel3 = carStatsEntity.getAcVar3().floatValue() * tt;
        float finalAccel = (finalConstant * carStatsEntity.getAcStock().floatValue()) + finalAccel1 + finalAccel2
                + finalAccel3;

        float finalHandling1 = carStatsEntity.getHaVar1().floatValue() * th;
        float finalHandling2 = carStatsEntity.getHaVar2().floatValue() * ta;
        float finalHandling3 = carStatsEntity.getHaVar3().floatValue() * tt;
        float finalHandling =
                (finalConstant * carStatsEntity.getHaStock().floatValue()) + finalHandling1 + finalHandling2
                        + finalHandling3;

        float finalClass = ((int) finalTopSpeed + (int) finalAccel + (int) finalHandling) / 3f;
        int finalClassInt = (int) finalClass;

        Integer finalClassHash = this.carClassRanges.get(finalClassInt);
        if (finalClassHash != null) {
            customCarEntity.setCarClassHash(finalClassHash);
            customCarEntity.setRating(finalClassInt);
        } else {
            this.logger.warn("Could not determine car class for {} (rating: {})", customCarEntity.getName(), finalClassInt);
        }
    }

    public List<CarClassEntity> getCarClassEntities() {
        return carClassEntities;
    }
}