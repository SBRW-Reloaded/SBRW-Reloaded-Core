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
import com.soapboxrace.jaxb.http.ArrayOfDragEntrantResult;
import com.soapboxrace.jaxb.http.DragArbitrationPacket;
import com.soapboxrace.jaxb.http.DragEntrantResult;
import com.soapboxrace.jaxb.http.DragEventResult;
import com.soapboxrace.jaxb.xmpp.XMPP_DragEntrantResultType;
import com.soapboxrace.jaxb.xmpp.XMPP_ResponseTypeDragEntrantResult;

import javax.inject.Inject;
import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;

@ApplicationScoped

@Transactional
public class EventResultDragBO extends EventResultBO<DragArbitrationPacket, DragEventResult> {

    @Inject
    private EventSessionDAO eventSessionDao;

    @Inject
    private EventDataDAO eventDataDao;

    @Inject
    private PersonaDAO personaDAO;

    @Inject
    private OpenFireSoapBoxCli openFireSoapBoxCli;

    @Inject
    private RewardDragBO rewardDragBO;

    @Inject
    private CarDamageBO carDamageBO;

    @Inject
    private AchievementBO achievementBO;

    @Inject
    private DNFTimerBO dnfTimerBO;

    protected DragEventResult handleInternal(EventSessionEntity eventSessionEntity, Long activePersonaId,
                                             DragArbitrationPacket dragArbitrationPacket) {
        Long eventSessionId = eventSessionEntity.getId();

        EventDataEntity eventDataEntity = eventDataDao.findByPersonaAndEventSessionId(activePersonaId, eventSessionId);
        
        // If no event data exists, return empty result (player aborted before joining)
        if (eventDataEntity == null) {
            return new DragEventResult();
        }

        XMPP_DragEntrantResultType xmppDragResult = new XMPP_DragEntrantResultType();
        xmppDragResult.setEventDurationInMilliseconds(dragArbitrationPacket.getEventDurationInMilliseconds());
        xmppDragResult.setEventSessionId(eventSessionId);
        xmppDragResult.setFinishReason(dragArbitrationPacket.getFinishReason());
        xmppDragResult.setPersonaId(activePersonaId);
        xmppDragResult.setRanking(eventDataEntity.getRank());
        xmppDragResult.setTopSpeed(dragArbitrationPacket.getTopSpeed());

        XMPP_ResponseTypeDragEntrantResult dragEntrantResultResponse = new XMPP_ResponseTypeDragEntrantResult();
        dragEntrantResultResponse.setDragEntrantResult(xmppDragResult);

        if (eventDataEntity.getFinishReason() != 0) {
            return new DragEventResult();
        }

        prepareBasicEventData(eventDataEntity, activePersonaId, dragArbitrationPacket, eventSessionEntity);
        
        eventDataEntity.setFractionCompleted(dragArbitrationPacket.getFractionCompleted());
        eventDataEntity.setLongestJumpDurationInMilliseconds(dragArbitrationPacket.getLongestJumpDurationInMilliseconds());
        eventDataEntity.setNumberOfCollisions(dragArbitrationPacket.getNumberOfCollisions());
        eventDataEntity.setPerfectStart(dragArbitrationPacket.getPerfectStart());
        eventDataEntity.setSumOfJumpsDurationInMilliseconds(dragArbitrationPacket.getSumOfJumpsDurationInMilliseconds());
        eventDataEntity.setTopSpeed(dragArbitrationPacket.getTopSpeed());
        eventSessionEntity.setEnded(System.currentTimeMillis());

        ArrayOfDragEntrantResult arrayOfDragEntrantResult = new ArrayOfDragEntrantResult();
        for (EventDataEntity racer : eventDataDao.getRacers(eventSessionId)) {
            DragEntrantResult dragEntrantResult = new DragEntrantResult();
            dragEntrantResult.setEventDurationInMilliseconds(racer.getEventDurationInMilliseconds());
            dragEntrantResult.setEventSessionId(eventSessionId);
            dragEntrantResult.setFinishReason(racer.getFinishReason());
            dragEntrantResult.setPersonaId(racer.getPersonaId());
            dragEntrantResult.setRanking(racer.getRank());
            dragEntrantResult.setTopSpeed(racer.getTopSpeed());
            arrayOfDragEntrantResult.getDragEntrantResult().add(dragEntrantResult);

            if (!racer.getPersonaId().equals(activePersonaId)) {
                asyncXmppBO.sendMessage(dragEntrantResultResponse, racer.getPersonaId());
                if (dragArbitrationPacket.getFinishReason() == 22 && eventDataEntity.getRank() == 1 && eventSessionEntity.getEvent().isDnfEnabled()) {
                    asyncXmppBO.sendEventTimingOut(eventSessionEntity.getId(), eventSessionEntity.getEvent().getDnfTimerTime(), racer.getPersonaId());
                    dnfTimerBO.scheduleDNF(eventSessionEntity, racer.getPersonaId());
                }
            }
        }

        PersonaEntity personaEntity = personaDAO.find(activePersonaId);
        AchievementTransaction transaction = achievementBO.createTransaction(activePersonaId);
        DragEventResult dragEventResult = new DragEventResult();
        
        eventSessionDao.update(eventSessionEntity);
        eventDataDao.update(eventDataEntity);

        dragEventResult.setAccolades(rewardDragBO.getAccolades(activePersonaId, dragArbitrationPacket,
                eventDataEntity, eventSessionEntity, transaction));
        dragEventResult.setDurability(carDamageBO.induceCarDamage(activePersonaId, dragArbitrationPacket,
                eventDataEntity.getEvent()));
        dragEventResult.setEntrants(arrayOfDragEntrantResult);
        dragEventResult.setEventId(eventDataEntity.getEvent().getId());
        dragEventResult.setEventSessionId(eventSessionId);
        dragEventResult.setPersonaId(activePersonaId);
        prepareRaceAgain(eventSessionEntity, activePersonaId, dragEventResult, dragArbitrationPacket);
        updateEventAchievements(eventDataEntity, eventSessionEntity, activePersonaId, dragArbitrationPacket, transaction);

        if (dragEventResult.getAccolades() != null && dragEventResult.getAccolades().getLuckyDrawInfo() != null) {
            dragEventResult.getAccolades().getLuckyDrawInfo().setCardDeck(CardDecks.forRank(eventDataEntity.getRank()));
        }

        commitEventAchievementsWithFinalRank(eventDataEntity, activePersonaId, transaction);

        if (eventSessionEntity.getLobby() != null && !eventSessionEntity.getLobby().getIsPrivate()) {
            matchmakingBO.resetIgnoredEvents(activePersonaId);
        }

        return dragEventResult;
    }

}
