/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2021.
 */

package com.soapboxrace.core.jpa;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "K_CREW")
public class KCrewEntity {
    @Id
    @Column(name = "ID", nullable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int crewId;

    private String tag;

    @Column(name = "cover_version")
    private Long coverVersion;

    @Column(name = "thumb_version")
    private Long thumbVersion;

    @Column(name = "district_id")
    private Long districtId;

    @Column(name = "create_date")
    private LocalDateTime createDate;

    @Column(name = "nb_member")
    private Long nbMember;

    @Column(name = "nb_points")
    private Long nbPoints;

    public String getTag() { 
        return tag; 
    }

    public void setTag(String tag) { 
        this.tag = tag; 
    }
}
