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
import com.soapboxrace.jaxb.http.ArrayOfTeamEscapeEntrantResult;
import com.soapboxrace.jaxb.http.TeamEscapeArbitrationPacket;
import com.soapboxrace.jaxb.http.TeamEscapeEntrantResult;
import com.soapboxrace.jaxb.http.TeamEscapeEventResult;
import com.soapboxrace.jaxb.xmpp.XMPP_ResponseTypeTeamEscapeEntrantResult;
import com.soapboxrace.jaxb.xmpp.XMPP_TeamEscapeEntrantResultType;

import javax.inject.Inject;
import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;

@ApplicationScoped

@Transactional
public class EventResultTeamEscapeBO extends EventResultBO<TeamEscapeArbitrationPacket, TeamEscapeEventResult> {

    @Inject
    private EventSessionDAO eventSessionDao;

    @Inject
    private EventDataDAO eventDataDao;

    @Inject
    private PersonaDAO personaDAO;

    @Inject
    private OpenFireSoapBoxCli openFireSoapBoxCli;

    @Inject
    private RewardTeamEscapeBO rewardTeamEscapeBO;

    @Inject
    private CarDamageBO carDamageBO;

    @Inject
    private AchievementBO achievementBO;

    @Inject
    private DNFTimerBO dnfTimerBO;

    protected TeamEscapeEventResult handleInternal(EventSessionEntity eventSessionEntity, Long activePersonaId,
                                                   TeamEscapeArbitrationPacket teamEscapeArbitrationPacket) {
        Long eventSessionId = eventSessionEntity.getId();

        XMPP_TeamEscapeEntrantResultType xmppTeamEscapeResult = new XMPP_TeamEscapeEntrantResultType();
        xmppTeamEscapeResult.setEventDurationInMilliseconds(teamEscapeArbitrationPacket.getEventDurationInMilliseconds());
        xmppTeamEscapeResult.setEventSessionId(eventSessionId);
        xmppTeamEscapeResult.setFinishReason(teamEscapeArbitrationPacket.getFinishReason());
        xmppTeamEscapeResult.setPersonaId(activePersonaId);

        XMPP_ResponseTypeTeamEscapeEntrantResult teamEscapeEntrantResultResponse = new XMPP_ResponseTypeTeamEscapeEntrantResult();
        teamEscapeEntrantResultResponse.setTeamEscapeEntrantResult(xmppTeamEscapeResult);

        EventDataEntity eventDataEntity = eventDataDao.findByPersonaAndEventSessionId(activePersonaId, eventSessionId);
        
        // If no event data exists, return empty result (player aborted before joining)
        if (eventDataEntity == null) {
            return new TeamEscapeEventResult();
        }

        if (eventDataEntity.getFinishReason() != 0) {
            return new TeamEscapeEventResult();
        }

        prepareBasicEventData(eventDataEntity, activePersonaId, teamEscapeArbitrationPacket, eventSessionEntity);
        eventDataEntity.setBustedCount(teamEscapeArbitrationPacket.getBustedCount());
        eventDataEntity.setCopsDeployed(teamEscapeArbitrationPacket.getCopsDeployed());
        eventDataEntity.setCopsDisabled(teamEscapeArbitrationPacket.getCopsDisabled());
        eventDataEntity.setCopsRammed(teamEscapeArbitrationPacket.getCopsRammed());
        eventDataEntity.setCostToState(teamEscapeArbitrationPacket.getCostToState());
        eventDataEntity.setDistanceToFinish(teamEscapeArbitrationPacket.getDistanceToFinish());
        eventDataEntity.setFractionCompleted(teamEscapeArbitrationPacket.getFractionCompleted());
        eventDataEntity.setInfractions(teamEscapeArbitrationPacket.getInfractions());
        eventDataEntity.setLongestJumpDurationInMilliseconds(teamEscapeArbitrationPacket.getLongestJumpDurationInMilliseconds());
        eventDataEntity.setNumberOfCollisions(teamEscapeArbitrationPacket.getNumberOfCollisions());
        eventDataEntity.setPerfectStart(teamEscapeArbitrationPacket.getPerfectStart());
        eventDataEntity.setRoadBlocksDodged(teamEscapeArbitrationPacket.getRoadBlocksDodged());
        eventDataEntity.setSpikeStripsDodged(teamEscapeArbitrationPacket.getSpikeStripsDodged());
        eventDataEntity.setSumOfJumpsDurationInMilliseconds(teamEscapeArbitrationPacket.getSumOfJumpsDurationInMilliseconds());
        eventDataEntity.setTopSpeed(teamEscapeArbitrationPacket.getTopSpeed());
        eventSessionEntity.setEnded(System.currentTimeMillis());

        ArrayOfTeamEscapeEntrantResult arrayOfTeamEscapeEntrantResult = new ArrayOfTeamEscapeEntrantResult();
        for (EventDataEntity racer : eventDataDao.getRacers(eventSessionId)) {
            TeamEscapeEntrantResult teamEscapeEntrantResult = new TeamEscapeEntrantResult();
            teamEscapeEntrantResult.setDistanceToFinish(racer.getDistanceToFinish());
            teamEscapeEntrantResult.setEventDurationInMilliseconds(racer.getEventDurationInMilliseconds());
            teamEscapeEntrantResult.setEventSessionId(eventSessionId);
            teamEscapeEntrantResult.setFinishReason(racer.getFinishReason());
            teamEscapeEntrantResult.setFractionCompleted(racer.getFractionCompleted());
            teamEscapeEntrantResult.setPersonaId(racer.getPersonaId());
            teamEscapeEntrantResult.setRanking(racer.getRank());
            arrayOfTeamEscapeEntrantResult.getTeamEscapeEntrantResult().add(teamEscapeEntrantResult);

            if (!racer.getPersonaId().equals(activePersonaId)) {
                asyncXmppBO.sendMessage(teamEscapeEntrantResultResponse, racer.getPersonaId());
                if ((teamEscapeArbitrationPacket.getFinishReason() == 518 ||
                        teamEscapeArbitrationPacket.getFinishReason() == 22) && eventDataEntity.getRank() == 1 && eventSessionEntity.getEvent().isDnfEnabled()) {
                    asyncXmppBO.sendEventTimingOut(eventSessionEntity.getId(), eventSessionEntity.getEvent().getDnfTimerTime(), racer.getPersonaId());
                    dnfTimerBO.scheduleDNF(eventSessionEntity, racer.getPersonaId());
                }
            }
        }

        PersonaEntity personaEntity = personaDAO.find(activePersonaId);
        AchievementTransaction transaction = achievementBO.createTransaction(activePersonaId);
        TeamEscapeEventResult teamEscapeEventResult = new TeamEscapeEventResult();
        
        eventSessionDao.update(eventSessionEntity);
        eventDataDao.update(eventDataEntity);

        teamEscapeEventResult.setAccolades(rewardTeamEscapeBO.getAccolades(activePersonaId,
                teamEscapeArbitrationPacket, eventDataEntity, eventSessionEntity, transaction));
        teamEscapeEventResult
                .setDurability(carDamageBO.induceCarDamage(activePersonaId, teamEscapeArbitrationPacket,
                        eventDataEntity.getEvent()));
        teamEscapeEventResult.setEntrants(arrayOfTeamEscapeEntrantResult);
        teamEscapeEventResult.setEventId(eventDataEntity.getEvent().getId());
        teamEscapeEventResult.setEventSessionId(eventSessionId);
        teamEscapeEventResult.setPersonaId(activePersonaId);
        prepareRaceAgain(eventSessionEntity, activePersonaId, teamEscapeEventResult, teamEscapeArbitrationPacket);

        if (teamEscapeArbitrationPacket.getBustedCount() == 0) {
            updateEventAchievements(eventDataEntity, eventSessionEntity, activePersonaId, teamEscapeArbitrationPacket, transaction);
        }

        if (teamEscapeEventResult.getAccolades() != null && teamEscapeEventResult.getAccolades().getLuckyDrawInfo() != null) {
            teamEscapeEventResult.getAccolades().getLuckyDrawInfo().setCardDeck(CardDecks.forRank(eventDataEntity.getRank()));
        }

        if (teamEscapeArbitrationPacket.getBustedCount() == 0) {
            commitEventAchievementsWithFinalRank(eventDataEntity, activePersonaId, transaction);
        }

        if (eventSessionEntity.getLobby() != null && !eventSessionEntity.getLobby().getIsPrivate()) {
            matchmakingBO.resetIgnoredEvents(activePersonaId);
        }

        return teamEscapeEventResult;
    }

}
