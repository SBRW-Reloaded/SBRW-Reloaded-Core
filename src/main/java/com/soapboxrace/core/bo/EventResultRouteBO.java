/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.bo;

import com.soapboxrace.core.dao.EventDataDAO;
import com.soapboxrace.core.dao.EventSessionDAO;
import com.soapboxrace.core.dao.PersonaDAO;
import com.soapboxrace.core.engine.EngineException;
import com.soapboxrace.core.engine.EngineExceptionCode;
import com.soapboxrace.core.jpa.EventDataEntity;
import com.soapboxrace.core.jpa.EventSessionEntity;
import com.soapboxrace.core.jpa.PersonaEntity;
import com.soapboxrace.core.xmpp.OpenFireSoapBoxCli;
import com.soapboxrace.core.xmpp.XmppChat;
import com.soapboxrace.core.xmpp.XmppEvent;
import com.soapboxrace.jaxb.http.ArrayOfRouteEntrantResult;
import com.soapboxrace.jaxb.http.RouteArbitrationPacket;
import com.soapboxrace.jaxb.http.RouteEntrantResult;
import com.soapboxrace.jaxb.http.RouteEventResult;
import com.soapboxrace.jaxb.xmpp.XMPP_ResponseTypeRouteEntrantResult;
import com.soapboxrace.jaxb.xmpp.XMPP_RouteEntrantResultType;

import javax.ejb.EJB;
import javax.ejb.Stateless;

@Stateless
public class EventResultRouteBO extends EventResultBO<RouteArbitrationPacket, RouteEventResult> {

    @EJB
    private EventSessionDAO eventSessionDao;

    @EJB
    private EventDataDAO eventDataDao;

    @EJB
    private PersonaDAO personaDAO;

    @EJB
    private OpenFireSoapBoxCli openFireSoapBoxCli;

    @EJB
    private RewardRouteBO rewardRouteBO;

    @EJB
    private CarDamageBO carDamageBO;

    @EJB
    private AchievementBO achievementBO;

    @EJB
    private DNFTimerBO dnfTimerBO;

    protected RouteEventResult handleInternal(EventSessionEntity eventSessionEntity, Long activePersonaId,
                                              RouteArbitrationPacket routeArbitrationPacket) {
        Long eventSessionId = eventSessionEntity.getId();

        // Calculer un rang temporaire pour XMPP (sera recalculé plus tard)
        int temporaryRank = calculateTemporaryRank(eventSessionEntity, activePersonaId, routeArbitrationPacket.getEventDurationInMilliseconds());

        XMPP_RouteEntrantResultType xmppRouteResult = new XMPP_RouteEntrantResultType();
        xmppRouteResult.setBestLapDurationInMilliseconds(routeArbitrationPacket.getBestLapDurationInMilliseconds());
        xmppRouteResult.setEventDurationInMilliseconds(routeArbitrationPacket.getEventDurationInMilliseconds());
        xmppRouteResult.setEventSessionId(eventSessionEntity.getId());
        xmppRouteResult.setFinishReason(routeArbitrationPacket.getFinishReason());
        xmppRouteResult.setPersonaId(activePersonaId);
        xmppRouteResult.setRanking(temporaryRank); // Utiliser le rang temporaire pour XMPP
        xmppRouteResult.setTopSpeed(routeArbitrationPacket.getTopSpeed());

        XMPP_ResponseTypeRouteEntrantResult routeEntrantResultResponse = new XMPP_ResponseTypeRouteEntrantResult();
        routeEntrantResultResponse.setRouteEntrantResult(xmppRouteResult);

        EventDataEntity eventDataEntity = eventDataDao.findByPersonaAndEventSessionId(activePersonaId, eventSessionId);

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
                XmppEvent xmppEvent = new XmppEvent(racer.getPersonaId(), openFireSoapBoxCli);
                xmppEvent.sendRaceEnd(routeEntrantResultResponse);

                if ((routeArbitrationPacket.getFinishReason() == 22) && temporaryRank == 1 && eventSessionEntity.getEvent().isDnfEnabled()) {
                    xmppEvent.sendEventTimingOut(eventSessionEntity);
                    dnfTimerBO.scheduleDNF(eventSessionEntity, racer.getPersonaId());
                }
            }
        }

        PersonaEntity personaEntity = personaDAO.find(activePersonaId);
        AchievementTransaction transaction = achievementBO.createTransaction(activePersonaId);
        RouteEventResult routeEventResult = new RouteEventResult();
        
        // Utiliser le rang temporaire pour les récompenses au lieu du rang 0 de la BDD
        routeArbitrationPacket.setRank(temporaryRank);
        routeEventResult.setAccolades(rewardRouteBO.getAccolades(activePersonaId, routeArbitrationPacket,
                eventDataEntity, eventSessionEntity, transaction));
        routeEventResult.setDurability(carDamageBO.induceCarDamage(activePersonaId, routeArbitrationPacket,
                eventDataEntity.getEvent()));
        routeEventResult.setEntrants(arrayOfRouteEntrantResult);
        routeEventResult.setEventId(eventDataEntity.getEvent().getId());
        routeEventResult.setEventSessionId(eventSessionId);
        routeEventResult.setPersonaId(activePersonaId);
        prepareRaceAgain(eventSessionEntity, routeEventResult, routeArbitrationPacket);

        updateEventAchievements(eventDataEntity, eventSessionEntity, activePersonaId, routeArbitrationPacket, transaction);
        // NE PAS committer les achievements ici - attendre le recalcul des rangs finaux

        eventSessionDao.update(eventSessionEntity);
        eventDataDao.update(eventDataEntity);

        // Recalculer tous les rangs maintenant que tous les résultats sont finalisés
        recalculateAllRanks(eventSessionEntity);
        
        // Maintenant que les rangs finaux sont calculés, committer les achievements
        commitEventAchievementsWithFinalRank(eventDataEntity, activePersonaId, transaction);

        if (eventSessionEntity.getLobby() != null && !eventSessionEntity.getLobby().getIsPrivate()) {
            matchmakingBO.resetIgnoredEvents(activePersonaId);
        }

        return routeEventResult;
    }
}
