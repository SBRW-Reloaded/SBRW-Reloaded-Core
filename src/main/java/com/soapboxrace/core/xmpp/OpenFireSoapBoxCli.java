/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.xmpp;

import com.soapboxrace.jaxb.util.JAXBUtility;

import javax.ejb.EJB;
import javax.ejb.Lock;
import javax.ejb.LockType;
import javax.ejb.Stateless;

@Stateless
@Lock(LockType.READ)
public class OpenFireSoapBoxCli {

    @EJB
    private OpenFireRestApiCli restApi;

    public void send(String msg, Long to) {
        restApi.sendMessage(to, msg);
    }

    public void send(Object object, Long to) {
        restApi.sendMessage(to, JAXBUtility.marshal(object));
    }
}