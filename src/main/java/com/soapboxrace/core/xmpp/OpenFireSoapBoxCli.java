/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.xmpp;

import com.soapboxrace.jaxb.util.JAXBUtility;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ejb.EJB;
import javax.ejb.Lock;
import javax.ejb.LockType;
import javax.ejb.Stateless;

@Stateless
@Lock(LockType.READ)
public class OpenFireSoapBoxCli {
    private static final Logger logger = LoggerFactory.getLogger(OpenFireSoapBoxCli.class);

    @EJB
    private OpenFireRestApiCli restApi;

    public void send(String msg, Long to) {
        try {
            restApi.sendMessage(to, msg);
        } catch (Exception e) {
            logger.error(String.format("Failed to send string message to PersonaId=%d: %s", to, e.getMessage()), e);
        }
    }

    public void send(Object object, Long to) {
        String objectType = object.getClass().getSimpleName();
        
        try {
            String xmlMessage = JAXBUtility.marshal(object);
            restApi.sendMessage(to, xmlMessage);
        } catch (Exception e) {
            logger.error(String.format("Failed to send object message (%s) to PersonaId=%d: %s", objectType, to, e.getMessage()), e);
        }
    }
}