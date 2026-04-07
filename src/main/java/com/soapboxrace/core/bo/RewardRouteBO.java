/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.bo;

import com.soapboxrace.core.bo.util.RewardVO;
import com.soapboxrace.core.dao.PersonaDAO;
import com.soapboxrace.core.jpa.*;
import com.soapboxrace.jaxb.http.Accolades;
import com.soapboxrace.jaxb.http.RouteArbitrationPacket;

import javax.inject.Inject;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Typed;
import javax.transaction.Transactional;

@ApplicationScoped
@Typed(RewardRouteBO.class)
@Transactional
public class RewardRouteBO extends RewardEventBO<RouteArbitrationPacket> {

    @Inject
    private PersonaDAO personaDao;

    @Inject
    private LegitRaceBO legitRaceBO;

    @Inject
    private LeaderboardBO leaderboardBO;

    public Accolades getAccolades(Long activePersonaId, RouteArbitrationPacket routeArbitrationPacket,
                                  EventDataEntity eventDataEntity, EventSessionEntity eventSessionEntity, AchievementTransaction achievementTransaction) {
        int finishReason = routeArbitrationPacket.getFinishReason();
        boolean legit = legitRaceBO.isLegit(activePersonaId, routeArbitrationPacket, eventSessionEntity, eventDataEntity);
        eventDataEntity.setLegit(legit);
        if (!legit || finishReason != 22) {
            return new Accolades();
        }        

        PersonaEntity personaEntity = personaDao.find(activePersonaId);
        RewardVO rewardVO = getRewardVO(personaEntity);
        EventRewardEntity eventRewardEntity = getRewardConfiguration(eventSessionEntity);

        setBaseReward(personaEntity, eventSessionEntity.getEvent(), eventRewardEntity, eventDataEntity, routeArbitrationPacket, rewardVO);
        setRankReward(eventRewardEntity, routeArbitrationPacket, rewardVO);
        setPerfectStartReward(eventRewardEntity, routeArbitrationPacket.getPerfectStart(), rewardVO);
        setTopSpeedReward(eventRewardEntity, routeArbitrationPacket.getTopSpeed(), rewardVO);
        setSkillMultiplierReward(personaEntity, rewardVO, SkillModRewardType.SOCIALITE);
        setMultiplierReward(eventRewardEntity, rewardVO);
        setAmplifierReward(personaEntity, rewardVO);

        applyRaceReward(rewardVO.getRep(), rewardVO.getCash(), personaEntity, true, achievementTransaction);

        //Set leaderboard things
        leaderboardBO.setupLeaderboard(activePersonaId, routeArbitrationPacket, eventSessionEntity, eventDataEntity);        

        return getAccolades(personaEntity, eventRewardEntity, routeArbitrationPacket, rewardVO);
    }

}
