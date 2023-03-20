/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.api;

import com.soapboxrace.core.api.util.Secured;
import com.soapboxrace.core.dao.CarClassListDAO;
import com.soapboxrace.core.jpa.CarClassListEntity;
import com.soapboxrace.jaxb.http.ArrayOfCarClass;
import com.soapboxrace.jaxb.http.CarClass;

import javax.ejb.EJB;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

@Path("/carclasses")
public class CarClasses {
    @EJB
    private CarClassListDAO CarClassListDAO;

    @GET
    @Secured
    @Produces(MediaType.APPLICATION_XML)
    public ArrayOfCarClass carClasses() {
        ArrayOfCarClass arrayOfCarClass = new ArrayOfCarClass();

        for (CarClassListEntity CarClassLists : CarClassListDAO.findAll()) {
            CarClass CarClassDef = new CarClass();
            CarClassDef.setCarClassHash(CarClassLists.getHash());
            CarClassDef.setMaxRating((short) CarClassLists.getMaxVal());
            CarClassDef.setMinRating((short) CarClassLists.getMinVal());
            arrayOfCarClass.getCarClass().add(CarClassDef);
        }

        return arrayOfCarClass;
    }
}