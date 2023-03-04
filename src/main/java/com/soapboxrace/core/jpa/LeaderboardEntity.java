package com.soapboxrace.core.jpa;

import javax.persistence.Entity;

import lombok.Getter;
import lombok.Setter;

@Entity
public class LeaderboardEntity {
    @Getter @Setter private Integer RANKING;
    @Getter @Setter private Long TIMES;
    @Getter @Setter private String PERSONANAME;
}