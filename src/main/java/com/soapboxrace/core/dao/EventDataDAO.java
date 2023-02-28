/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.dao;

import com.soapboxrace.core.dao.util.LongKeyedDAO;
import com.soapboxrace.core.jpa.EventDataEntity;

import javax.ejb.Stateless;
import javax.persistence.TypedQuery;
import javax.persistence.Query;
import java.util.List;

@Stateless
public class EventDataDAO extends LongKeyedDAO<EventDataEntity> {

    public EventDataDAO() {
        super(EventDataEntity.class);
    }

    public List<EventDataEntity> getRacers(Long eventSessionId) {
        TypedQuery<EventDataEntity> query = entityManager.createNamedQuery("EventDataEntity.getRacers",
                EventDataEntity.class);
        query.setParameter("eventSessionId", eventSessionId);
        return query.getResultList();
    }

    public EventDataEntity findByPersonaAndEventSessionId(Long personaId, Long eventSessionId) {
        TypedQuery<EventDataEntity> query = entityManager.createNamedQuery("EventDataEntity" +
                ".findByPersonaAndEventSessionId", EventDataEntity.class);
        query.setParameter("personaId", personaId);
        query.setParameter("eventSessionId", eventSessionId);

        List<EventDataEntity> resultList = query.getResultList();
        return !resultList.isEmpty() ? resultList.get(0) : null;
    }

    //Some leaderboard stuff, hardcoded
    @SuppressWarnings("unchecked")
    public List<Object[]> getResultByNameAndEventId_powerups(Long eventId, String personaName) {
        return entityManager.createNativeQuery("SELECT RANKING FROM (SELECT ROW_NUMBER() OVER (ORDER BY EVENT_DATA.eventDurationInMilliseconds ASC) AS RANKING, PERSONA.name AS PERSONANAME, COUNT(BAN.active) AS BANNED FROM EVENT_DATA INNER JOIN PERSONA ON PERSONA.ID = EVENT_DATA.personaId LEFT JOIN BAN ON BAN.user_id = PERSONA.USERID LEFT JOIN  USED_POWERUP ON USED_POWERUP.eventSessionId = EVENT_DATA.eventSessionId AND PERSONA.ID = USED_POWERUP.personaId WHERE EVENT_DATA.EVENTID = "+ eventId +" AND EVENT_DATA.finishReason = 22 AND EVENT_DATA.hacksDetected IN(0,22,32) AND (SELECT USED_POWERUP.eventSessionId FROM USED_POWERUP WHERE USED_POWERUP.personaId = PERSONA.ID AND USED_POWERUP.eventSessionId = EVENT_DATA.eventSessionId LIMIT 1) IS NULL GROUP BY PERSONA.name HAVING 1 AND BANNED = 0) x WHERE PERSONANAME = \""+personaName+"\"").getResultList();
    }

    @SuppressWarnings("unchecked")
    public List<Object[]> getResultByNameAndEventId_nopowerups(Long eventId, String personaName) {
        return entityManager.createNativeQuery("SELECT RANKING FROM (SELECT ROW_NUMBER() OVER (ORDER BY EVENT_DATA.eventDurationInMilliseconds ASC) AS RANKING, PERSONA.name AS PERSONANAME, COUNT(BAN.active) AS BANNED FROM EVENT_DATA INNER JOIN PERSONA ON PERSONA.ID = EVENT_DATA.personaId LEFT JOIN BAN ON BAN.user_id = PERSONA.USERID LEFT JOIN  USED_POWERUP ON USED_POWERUP.eventSessionId = EVENT_DATA.eventSessionId AND PERSONA.ID = USED_POWERUP.personaId WHERE EVENT_DATA.EVENTID = "+ eventId +" AND EVENT_DATA.finishReason = 22 AND EVENT_DATA.hacksDetected IN(0,22,32) GROUP BY PERSONA.name HAVING 1 AND BANNED = 0) x WHERE PERSONANAME = \""+personaName+"\"").getResultList();
    }

}
