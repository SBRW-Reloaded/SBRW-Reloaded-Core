/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2021.
 */

package com.soapboxrace.core.jpa;

import java.time.LocalDateTime;

import javax.persistence.*;

import lombok.Getter;
import lombok.Setter;
 
@Entity
@Table(name = "RANKED")
public class RankedEntity {
    @Id
    @Column(name = "id", nullable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Getter @Setter private int rankedId;

    @Getter @Setter private Integer personaId;
	@Getter @Setter private Integer pointsWon;
	@Getter @Setter private Integer pointsLost;
    @Getter @Setter private LocalDateTime date;
}
 