package com.soapboxrace.core.jpa;

public class LeaderboardEntity {
    private Integer RANKING;
    private Long TIMES;
    private String PERSONANAME;

    public Integer getRanking() {
        return RANKING;
    }

    public Long getTimes() {
        return TIMES;
    }

    public String getPersonaName() {
        return PERSONANAME;
    }
}
