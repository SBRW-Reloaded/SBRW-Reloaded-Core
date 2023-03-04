package com.soapboxrace.core.dao;

import com.soapboxrace.core.jpa.LeaderboardEntity;
 
import javax.ejb.Stateless;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.Query;
 
@Stateless
public class LeaderboardDAO {

    private EntityManager entityManager;

    //Some leaderboard stuff, hardcoded
    @SuppressWarnings("unchecked")
    public LeaderboardEntity getResultByNameAndEventId(int eventId, String personaName, Boolean isPowerup) {
        String appendPuCommand = isPowerup == true ? "AND (SELECT USED_POWERUP.eventSessionId FROM USED_POWERUP WHERE USED_POWERUP.personaId = PERSONA.ID AND USED_POWERUP.eventSessionId = EVENT_DATA.eventSessionId LIMIT 1) IS NULL " : "";

        String queries = "SELECT RANKING, TIMES, PERSONANAME FROM (SELECT ROW_NUMBER() OVER (ORDER BY EVENT_DATA.eventDurationInMilliseconds ASC) AS RANKING, PERSONA.name AS PERSONANAME, COUNT(BAN.active) AS BANNED, EVENT_DATA.eventDurationInMilliseconds AS TIMES FROM EVENT_DATA INNER JOIN PERSONA ON PERSONA.ID = EVENT_DATA.personaId LEFT JOIN BAN ON BAN.user_id = PERSONA.USERID WHERE EVENT_DATA.EVENTID = '"+eventId+"' AND EVENT_DATA.finishReason = 22 AND EVENT_DATA.hacksDetected IN(0,22,32) "+appendPuCommand+"GROUP BY PERSONA.name HAVING 1 AND BANNED = 0) x WHERE PERSONANAME = '"+personaName+"'";

        Query query = entityManager.createNativeQuery(queries, LeaderboardEntity.class);

        System.out.println(queries);

        List<LeaderboardEntity> resultList = query.getResultList();
        return !resultList.isEmpty() ? resultList.get(0) : null;
    }

    @SuppressWarnings("unchecked")
    public LeaderboardEntity getBestPlayerByEventId(int eventId, Boolean isPowerup) {
        String appendPuCommand = isPowerup == true ? "AND (SELECT USED_POWERUP.eventSessionId FROM USED_POWERUP WHERE USED_POWERUP.personaId = PERSONA.ID AND USED_POWERUP.eventSessionId = EVENT_DATA.eventSessionId LIMIT 1) IS NULL " : "";

        String queries = "SELECT RANKING, TIMES, PERSONANAME FROM (SELECT ROW_NUMBER() OVER (ORDER BY EVENT_DATA.eventDurationInMilliseconds ASC) AS RANKING, PERSONA.name AS PERSONANAME, COUNT(BAN.active) AS BANNED, EVENT_DATA.eventDurationInMilliseconds AS TIMES FROM EVENT_DATA INNER JOIN PERSONA ON PERSONA.ID = EVENT_DATA.personaId LEFT JOIN BAN ON BAN.user_id = PERSONA.USERID WHERE EVENT_DATA.EVENTID = '"+eventId+"' AND EVENT_DATA.finishReason = 22 AND EVENT_DATA.hacksDetected IN(0,22,32) "+appendPuCommand+" GROUP BY PERSONA.name HAVING 1 AND BANNED = 0) x WHERE RANKING = 1";

        System.out.println(queries);
        Query query = entityManager.createNativeQuery(queries, LeaderboardEntity.class);

        List<LeaderboardEntity> resultList = query.getResultList();
        return !resultList.isEmpty() ? resultList.get(0) : null;
    }
}
 