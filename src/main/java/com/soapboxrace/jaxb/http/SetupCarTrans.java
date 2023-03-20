package com.soapboxrace.jaxb.http;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlType;
 
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "SetupCarTrans", propOrder = {
    "baseCar",
    "physicsProfileHash",
    "carClassHash",
    "performanceParts",
    "skillModParts",
    "visualParts"
})

public class SetupCarTrans {
    protected int baseCar;
    protected int physicsProfileHash;
    protected int carClassHash;
    protected String performanceParts;
    protected String skillModParts;
    protected String visualParts;
 
    public int getBaseCar() {
        return baseCar;
    }

    public void setBaseCar(int value) {
        this.baseCar = value;
    }
 
    public int getCarClassHash() {
        return carClassHash;
    }

    public void setCarClassHash(int value) {
        this.carClassHash = value;
    }
 
    public String getPerformanceParts() {
        return performanceParts;
    }
 
    public void setPerformanceParts(String value) {
        this.performanceParts = value;
    }

    public int getPhysicsProfileHash() {
        return physicsProfileHash;
    }
 
    public void setPhysicsProfileHash(int value) {
        this.physicsProfileHash = value;
    }
 
    public String getSkillModParts() {
        return skillModParts;
    }

    public void setSkillModParts(String value) {
        this.skillModParts = value;
    }
 
    public String getVisualParts() {
        return visualParts;
    }
 
    public void setVisualParts(String value) {
        this.visualParts = value;
    }
}
 