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
        logger.debug(String.format("Sending string message to PersonaId=%d: %s", to, msg));
        try {
            restApi.sendMessage(to, msg);
            logger.debug(String.format("String message sent successfully to PersonaId=%d", to));
        } catch (Exception e) {
            logger.error(String.format("Failed to send string message to PersonaId=%d: %s", to, e.getMessage()), e);
        }
    }

    public void send(Object object, Long to) {
        String objectType = object.getClass().getSimpleName();
        logger.info(String.format("Sending object message (%s) to PersonaId=%d", objectType, to));
        
        try {
            String xmlMessage = JAXBUtility.marshal(object);
            logger.debug(String.format("Marshalled XML for PersonaId=%d: %s", to, xmlMessage));
            
            restApi.sendMessage(to, xmlMessage);
            logger.info(String.format("Object message (%s) sent successfully to PersonaId=%d", objectType, to));
        } catch (Exception e) {
            logger.error(String.format("Failed to send object message (%s) to PersonaId=%d: %s", objectType, to, e.getMessage()), e);
        }
    }
}