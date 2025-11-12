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
import com.soapboxrace.core.xmpp.XmppEvent;
import com.soapboxrace.jaxb.http.ArrayOfDragEntrantResult;
import com.soapboxrace.jaxb.http.DragArbitrationPacket;
import com.soapboxrace.jaxb.http.DragEntrantResult;
import com.soapboxrace.jaxb.http.DragEventResult;
import com.soapboxrace.jaxb.xmpp.XMPP_DragEntrantResultType;
import com.soapboxrace.jaxb.xmpp.XMPP_ResponseTypeDragEntrantResult;

import javax.ejb.EJB;
import javax.ejb.Stateless;

@Stateless
public class EventResultDragBO extends EventResultBO<DragArbitrationPacket, DragEventResult> {

    @EJB
    private EventSessionDAO eventSessionDao;

    @EJB
    private EventDataDAO eventDataDao;

    @EJB
    private PersonaDAO personaDAO;

    @EJB
    private OpenFireSoapBoxCli openFireSoapBoxCli;

    @EJB
    private RewardDragBO rewardDragBO;

    @EJB
    private CarDamageBO carDamageBO;

    @EJB
    private AchievementBO achievementBO;

    @EJB
    private DNFTimerBO dnfTimerBO;

    protected DragEventResult handleInternal(EventSessionEntity eventSessionEntity, Long activePersonaId,
                                             DragArbitrationPacket dragArbitrationPacket) {
        Long eventSessionId = eventSessionEntity.getId();

        // Calculer un rang temporaire pour XMPP (sera recalculé plus tard)
        int temporaryRank = calculateTemporaryRank(eventSessionEntity, activePersonaId, dragArbitrationPacket.getEventDurationInMilliseconds());

        XMPP_DragEntrantResultType xmppDragResult = new XMPP_DragEntrantResultType();
        xmppDragResult.setEventDurationInMilliseconds(dragArbitrationPacket.getEventDurationInMilliseconds());
        xmppDragResult.setEventSessionId(eventSessionId);
        xmppDragResult.setFinishReason(dragArbitrationPacket.getFinishReason());
        xmppDragResult.setPersonaId(activePersonaId);
        xmppDragResult.setRanking(temporaryRank); // Utiliser le rang temporaire
        xmppDragResult.setTopSpeed(dragArbitrationPacket.getTopSpeed());

        XMPP_ResponseTypeDragEntrantResult dragEntrantResultResponse = new XMPP_ResponseTypeDragEntrantResult();
        dragEntrantResultResponse.setDragEntrantResult(xmppDragResult);

        EventDataEntity eventDataEntity = eventDataDao.findByPersonaAndEventSessionId(activePersonaId, eventSessionId);

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
                XmppEvent xmppEvent = new XmppEvent(racer.getPersonaId(), openFireSoapBoxCli);
                xmppEvent.sendDragEnd(dragEntrantResultResponse);
                if (dragArbitrationPacket.getFinishReason() == 22 && temporaryRank == 1 && eventSessionEntity.getEvent().isDnfEnabled()) {
                    xmppEvent.sendEventTimingOut(eventSessionEntity);
                    dnfTimerBO.scheduleDNF(eventSessionEntity, racer.getPersonaId());
                }
            }
        }

        PersonaEntity personaEntity = personaDAO.find(activePersonaId);
        AchievementTransaction transaction = achievementBO.createTransaction(activePersonaId);
        DragEventResult dragEventResult = new DragEventResult();
        
        // Utiliser le rang temporaire pour les récompenses au lieu du rang 0 de la BDD
        dragArbitrationPacket.setRank(temporaryRank);
        dragEventResult.setAccolades(rewardDragBO.getAccolades(activePersonaId, dragArbitrationPacket,
                eventDataEntity, eventSessionEntity, transaction));
        dragEventResult.setDurability(carDamageBO.induceCarDamage(activePersonaId, dragArbitrationPacket,
                eventDataEntity.getEvent()));
        dragEventResult.setEntrants(arrayOfDragEntrantResult);
        dragEventResult.setEventId(eventDataEntity.getEvent().getId());
        dragEventResult.setEventSessionId(eventSessionId);
        dragEventResult.setPersonaId(activePersonaId);
        prepareRaceAgain(eventSessionEntity, dragEventResult, dragArbitrationPacket);
        updateEventAchievements(eventDataEntity, eventSessionEntity, activePersonaId, dragArbitrationPacket, transaction);
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

        return dragEventResult;
    }

}
