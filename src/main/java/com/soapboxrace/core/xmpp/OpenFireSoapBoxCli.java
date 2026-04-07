/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.xmpp;

import com.soapboxrace.jaxb.util.JAXBUtility;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;

@ApplicationScoped

@Transactional
public class OpenFireSoapBoxCli {
    private static final Logger logger = LoggerFactory.getLogger(OpenFireSoapBoxCli.class);
    
    // Augmentation des retries et délais pour connexions LTE/4G instables
    private static final int MAX_RETRIES = 5; // Augmenté de 2 à 5
    private static final int RETRY_DELAY_MS = 150; // Augmenté de 50ms à 150ms

    @Inject
    private OpenFireRestApiCli restApi;

    /**
     * Envoie un message texte avec retry automatique
     */
    public void send(String msg, Long to) {
        if (msg == null || to == null || to.equals(0L)) {
            logger.warn("Cannot send message: invalid parameters (msg={}, to={})", 
                      msg != null ? "present" : "null", to);
            return;
        }
        
        sendWithRetry(() -> {
            restApi.sendMessage(to, msg);
            logger.trace("String message sent to PersonaId={}", to);
        }, "String", to);
    }

    /**
     * Envoie un objet JAXB avec retry automatique
     */
    public void send(Object object, Long to) {
        if (object == null || to == null || to.equals(0L)) {
            logger.warn("Cannot send message: invalid parameters (object={}, to={})", 
                      object != null ? object.getClass().getSimpleName() : "null", to);
            return;
        }
        
        String objectType = object.getClass().getSimpleName();
        
        sendWithRetry(() -> {
            String xmlMessage = JAXBUtility.marshal(object);
            restApi.sendMessage(to, xmlMessage);
            logger.trace("Object message ({}) sent to PersonaId={}", objectType, to);
        }, objectType, to);
    }
    
    /**
     * M\u00e9thode utilitaire pour envoyer avec retry automatique
     */
    private void sendWithRetry(MessageSender sender, String messageType, Long targetPersonaId) {
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                sender.send();
                return; // Succ\u00e8s
            } catch (Exception e) {
                lastException = e;
                
                if (attempt < MAX_RETRIES) {
                    logger.debug("Retry {}/{} sending {} to PersonaId={}: {}", 
                               attempt, MAX_RETRIES, messageType, targetPersonaId, e.getMessage());
                    try {
                        Thread.sleep(RETRY_DELAY_MS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.warn("Interrupted while retrying message send to PersonaId={}", targetPersonaId);
                        break;
                    }
                }
            }
        }
        
        // Tous les essais ont \u00e9chou\u00e9
        logger.error("Failed to send {} message to PersonaId={} after {} attempts: {}", 
                   messageType, targetPersonaId, MAX_RETRIES, 
                   lastException != null ? lastException.getMessage() : "unknown error", 
                   lastException);
    }
    
    @FunctionalInterface
    private interface MessageSender {
        void send() throws Exception;
    }
}