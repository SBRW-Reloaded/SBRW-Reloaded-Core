/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.jpa;

import javax.persistence.*;

@Entity
@Table(name = "CAR_CLASSLIST")
@NamedQueries({
    @NamedQuery(name = "CarClassListEntity.findByRating", query = "SELECT obj fROM CarClassListEntity obj WHERE obj.minVal <= :rating AND obj.maxVal >= :rating"),
    @NamedQuery(name = "CarClassListEntity.findAll", query = "SELECT obj fROM CarClassListEntity obj")
})
public class CarClassListEntity {

    @Id
    private int id;

    private int hash;
    private String name;
    private int minVal;
    private int maxVal;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getHash() {
        return hash;
    }

    public void setHash(int hash) {
        this.hash = hash;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getMinVal() {
        return minVal;
    }

    public void setMinVal(int minVal) {
        this.minVal = minVal;
    }

    public int getMaxVal() {
        return maxVal;
    }

    public void setMaxVal(int maxVal) {
        this.maxVal = maxVal;
    }
}
