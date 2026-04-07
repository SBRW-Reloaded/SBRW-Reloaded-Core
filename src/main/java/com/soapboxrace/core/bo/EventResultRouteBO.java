/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.bo;

import com.soapboxrace.core.bo.util.CardDecks;
import com.soapboxrace.core.dao.EventDataDAO;
import com.soapboxrace.core.dao.EventSessionDAO;
import com.soapboxrace.core.dao.PersonaDAO;
import com.soapboxrace.core.engine.EngineException;
import com.soapboxrace.core.engine.EngineExceptionCode;
import com.soapboxrace.core.jpa.EventDataEntity;
import com.soapboxrace.core.jpa.EventSessionEntity;
import com.soapboxrace.core.jpa.PersonaEntity;
import com.soapboxrace.core.xmpp.OpenFireSoapBoxCli;
import com.soapboxrace.jaxb.http.ArrayOfRouteEntrantResult;
import com.soapboxrace.jaxb.http.RouteArbitrationPacket;
import com.soapboxrace.jaxb.http.RouteEntrantResult;
import com.soapboxrace.jaxb.http.RouteEventResult;
import com.soapboxrace.jaxb.xmpp.XMPP_ResponseTypeRouteEntrantResult;
import com.soapboxrace.jaxb.xmpp.XMPP_RouteEntrantResultType;

import javax.inject.Inject;
import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;

@ApplicationScoped

@Transactional
public class EventResultRouteBO extends EventResultBO<RouteArbitrationPacket, RouteEventResult> {

    @Inject
    private EventSessionDAO eventSessionDao;

    @Inject
    private EventDataDAO eventDataDao;

    @Inject
    private PersonaDAO personaDAO;

    @Inject
    private OpenFireSoapBoxCli openFireSoapBoxCli;

    @Inject
    private RewardRouteBO rewardRouteBO;

    @Inject
    private CarDamageBO carDamageBO;

    @Inject
    private AchievementBO achievementBO;

    @Inject
    private DNFTimerBO dnfTimerBO;

    protected RouteEventResult handleInternal(EventSessionEntity eventSessionEntity, Long activePersonaId,
                                              RouteArbitrationPacket routeArbitrationPacket) {
        Long eventSessionId = eventSessionEntity.getId();

        EventDataEntity eventDataEntity = eventDataDao.findByPersonaAndEventSessionId(activePersonaId, eventSessionId);
        
        // If no event data exists, return empty result (player aborted before joining)
        if (eventDataEntity == null) {
            return new RouteEventResult();
        }

        XMPP_RouteEntrantResultType xmppRouteResult = new XMPP_RouteEntrantResultType();
        xmppRouteResult.setBestLapDurationInMilliseconds(routeArbitrationPacket.getBestLapDurationInMilliseconds());
        xmppRouteResult.setEventDurationInMilliseconds(routeArbitrationPacket.getEventDurationInMilliseconds());
        xmppRouteResult.setEventSessionId(eventSessionEntity.getId());
        xmppRouteResult.setFinishReason(routeArbitrationPacket.getFinishReason());
        xmppRouteResult.setPersonaId(activePersonaId);
        xmppRouteResult.setRanking(eventDataEntity.getRank());
        xmppRouteResult.setTopSpeed(routeArbitrationPacket.getTopSpeed());

        XMPP_ResponseTypeRouteEntrantResult routeEntrantResultResponse = new XMPP_ResponseTypeRouteEntrantResult();
        routeEntrantResultResponse.setRouteEntrantResult(xmppRouteResult);

        if (eventDataEntity.getFinishReason() != 0) {
            return new RouteEventResult();
        }

        prepareBasicEventData(eventDataEntity, activePersonaId, routeArbitrationPacket, eventSessionEntity);

        eventDataEntity.setBestLapDurationInMilliseconds(routeArbitrationPacket.getBestLapDurationInMilliseconds());
        eventDataEntity.setFractionCompleted(routeArbitrationPacket.getFractionCompleted());
        eventDataEntity.setLongestJumpDurationInMilliseconds(routeArbitrationPacket.getLongestJumpDurationInMilliseconds());
        eventDataEntity.setNumberOfCollisions(routeArbitrationPacket.getNumberOfCollisions());
        eventDataEntity.setPerfectStart(routeArbitrationPacket.getPerfectStart());
        eventDataEntity.setSumOfJumpsDurationInMilliseconds(routeArbitrationPacket.getSumOfJumpsDurationInMilliseconds());
        eventDataEntity.setTopSpeed(routeArbitrationPacket.getTopSpeed());
        eventSessionEntity.setEnded(System.currentTimeMillis());

        ArrayOfRouteEntrantResult arrayOfRouteEntrantResult = new ArrayOfRouteEntrantResult();
        for (EventDataEntity racer : eventDataDao.getRacers(eventSessionId)) {
            RouteEntrantResult routeEntrantResult = new RouteEntrantResult();
            routeEntrantResult.setBestLapDurationInMilliseconds(racer.getBestLapDurationInMilliseconds());
            routeEntrantResult.setEventDurationInMilliseconds(racer.getEventDurationInMilliseconds());
            routeEntrantResult.setEventSessionId(eventSessionId);
            routeEntrantResult.setFinishReason(racer.getFinishReason());
            routeEntrantResult.setPersonaId(racer.getPersonaId());
            routeEntrantResult.setRanking(racer.getRank());
            routeEntrantResult.setTopSpeed(racer.getTopSpeed());
            arrayOfRouteEntrantResult.getRouteEntrantResult().add(routeEntrantResult);

            if (!racer.getPersonaId().equals(activePersonaId)) {
                asyncXmppBO.sendMessage(routeEntrantResultResponse, racer.getPersonaId());

                if ((routeArbitrationPacket.getFinishReason() == 22) && eventDataEntity.getRank() == 1 && eventSessionEntity.getEvent().isDnfEnabled()) {
                    asyncXmppBO.sendEventTimingOut(eventSessionEntity.getId(), eventSessionEntity.getEvent().getDnfTimerTime(), racer.getPersonaId());
                    dnfTimerBO.scheduleDNF(eventSessionEntity, racer.getPersonaId());
                }
            }
        }

        PersonaEntity personaEntity = personaDAO.find(activePersonaId);
        AchievementTransaction transaction = achievementBO.createTransaction(activePersonaId);
        RouteEventResult routeEventResult = new RouteEventResult();
        
        eventSessionDao.update(eventSessionEntity);
        eventDataDao.update(eventDataEntity);

        routeEventResult.setAccolades(rewardRouteBO.getAccolades(activePersonaId, routeArbitrationPacket,
                eventDataEntity, eventSessionEntity, transaction));
        routeEventResult.setDurability(carDamageBO.induceCarDamage(activePersonaId, routeArbitrationPacket,
                eventDataEntity.getEvent()));
        routeEventResult.setEntrants(arrayOfRouteEntrantResult);
        routeEventResult.setEventId(eventDataEntity.getEvent().getId());
        routeEventResult.setEventSessionId(eventSessionId);
        routeEventResult.setPersonaId(activePersonaId);
        prepareRaceAgain(eventSessionEntity, activePersonaId, routeEventResult, routeArbitrationPacket);

        updateEventAchievements(eventDataEntity, eventSessionEntity, activePersonaId, routeArbitrationPacket, transaction);

        if (routeEventResult.getAccolades() != null && routeEventResult.getAccolades().getLuckyDrawInfo() != null) {
            routeEventResult.getAccolades().getLuckyDrawInfo().setCardDeck(CardDecks.forRank(eventDataEntity.getRank()));
        }

        commitEventAchievementsWithFinalRank(eventDataEntity, activePersonaId, transaction);

        if (eventSessionEntity.getLobby() != null && !eventSessionEntity.getLobby().getIsPrivate()) {
            matchmakingBO.resetIgnoredEvents(activePersonaId);
        }

        return routeEventResult;
    }
}
