package com.soapboxrace.core.bo;

import com.soapboxrace.core.xmpp.OpenFireSoapBoxCli;
import com.soapboxrace.jaxb.xmpp.XMPP_EventTimingOutType;
import com.soapboxrace.jaxb.xmpp.XMPP_ResponseTypeEventTimingOut;

import javax.ejb.Asynchronous;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.inject.Inject;

/**
 * Fire-and-forget asynchronous XMPP notification sender.
 * Used to send race-end notifications outside the caller's JTA transaction,
 * preventing synchronous network I/O (with retry/sleep) from extending
 * the transaction duration and causing RollbackException timeouts.
 */
@Stateless
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class AsyncXmppBO {

    @Inject
    private OpenFireSoapBoxCli openFireSoapBoxCli;

    @Asynchronous
    public void sendMessage(Object message, Long personaId) {
        openFireSoapBoxCli.send(message, personaId);
    }

    @Asynchronous
    public void sendEventTimingOut(Long eventSessionId, int dnfTimerTime, Long personaId) {
        XMPP_EventTimingOutType eventTimingOut = new XMPP_EventTimingOutType();
        eventTimingOut.setEventSessionId(eventSessionId);
        eventTimingOut.setTimeInMilliseconds(dnfTimerTime);
        XMPP_ResponseTypeEventTimingOut eventTimingOutResponse = new XMPP_ResponseTypeEventTimingOut();
        eventTimingOutResponse.setEventTimingOut(eventTimingOut);
        openFireSoapBoxCli.send(eventTimingOutResponse, personaId);
    }
}
