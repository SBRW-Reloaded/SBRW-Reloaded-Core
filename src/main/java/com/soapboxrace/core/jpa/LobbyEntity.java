/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.jpa;

import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import org.hibernate.annotations.LazyCollection;
import org.hibernate.annotations.LazyCollectionOption;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "LOBBY")
@NamedQueries({ //
        @NamedQuery(name = "LobbyEntity.findAllOpen", query = "SELECT obj FROM LobbyEntity obj JOIN FETCH obj.event e WHERE obj.isActive = true and obj.isPrivate = false and obj.startedTime between :dateTime1 and :dateTime2 and size(obj.entrants) < obj.event.maxPlayers and size(obj.entrants) > 0"),
        @NamedQuery(name = "LobbyEntity.findAllOpenByCarClass", query = "SELECT obj FROM LobbyEntity obj JOIN FETCH obj.event e WHERE obj.isActive = true and obj.isPrivate = false and :level >= e.minLevel and :level <= e.maxLevel and obj.startedTime between :dateTime1 and :dateTime2 and (obj.event.carClassHash = 607077938 or obj.event.carClassHash = :carClassHash ) and size(obj.entrants) < obj.event.maxPlayers and size(obj.entrants) > 0"),
        @NamedQuery(name = "LobbyEntity.findAllOpenByLevel", query = "SELECT obj FROM LobbyEntity obj JOIN FETCH obj.event e WHERE obj.isActive = true and :level >= e.minLevel and :level <= e.maxLevel and obj.startedTime between :dateTime1 and :dateTime2 and obj.isPrivate = false and size(obj.entrants) < obj.event.maxPlayers and size(obj.entrants) > 0"),
        @NamedQuery(name = "LobbyEntity.findByEventStarted", query = "SELECT obj FROM LobbyEntity obj JOIN FETCH obj.event e WHERE obj.isActive = true and obj.event = :event AND obj.startedTime between :dateTime1 AND :dateTime2 AND obj.isPrivate = false AND size(obj.entrants) < obj.event.maxPlayers and size(obj.entrants) > 0"), //
        @NamedQuery(name = "LobbyEntity.findByEventAndPersona", query = "SELECT obj FROM LobbyEntity obj JOIN FETCH obj.event e WHERE obj.isActive = true and obj.event = :event AND obj.startedTime between :dateTime1 AND :dateTime2 AND obj.isPrivate = true AND obj.personaId = :personaId") //
})
public class LobbyEntity {

    @Id
    @Column(name = "ID", nullable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "EVENTID", referencedColumnName = "ID", foreignKey = @ForeignKey(name = "FK_LOBBY_EVENT_EVENTID"))
    private EventEntity event;

    @OneToMany(mappedBy = "lobby", targetEntity = LobbyEntrantEntity.class, cascade = CascadeType.MERGE, fetch = FetchType.EAGER)
    @LazyCollection(LazyCollectionOption.FALSE)
    @Fetch(FetchMode.JOIN)
    private List<LobbyEntrantEntity> entrants = new ArrayList<>();

    private LocalDateTime startedTime;

    private Boolean isPrivate;

    private Long personaId;
    
    @Column(name = "LOCKED_CAR_CLASS_HASH")
    private Integer lockedCarClassHash;
    
    @Column(name = "IS_ACTIVE")
    private Boolean isActive = true;
    
    @Transient
    private boolean hasHadPlayers = false;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public EventEntity getEvent() {
        return event;
    }

    public void setEvent(EventEntity event) {
        this.event = event;
    }

    public List<LobbyEntrantEntity> getEntrants() {
        return entrants;
    }

    public void setEntrants(List<LobbyEntrantEntity> entrants) {
        this.entrants = entrants;
    }

    public LocalDateTime getStartedTime() {
        return startedTime;
    }

    public void setStartedTime(LocalDateTime startedTime) {
        this.startedTime = startedTime;
    }

    public Boolean getIsPrivate() {
        return isPrivate;
    }

    public void setIsPrivate(Boolean isPrivate) {
        this.isPrivate = isPrivate;
    }

    public Long getPersonaId() {
        return personaId;
    }

    public void setPersonaId(Long personaId) {
        this.personaId = personaId;
    }

    public Integer getLockedCarClassHash() {
        return lockedCarClassHash;
    }

    public void setLockedCarClassHash(Integer lockedCarClassHash) {
        this.lockedCarClassHash = lockedCarClassHash;
    }

    public int getLobbyCountdownInMilliseconds(int baseTime) {
        if (startedTime != null) {
            return (int) (baseTime - (System.currentTimeMillis() - (startedTime.toEpochSecond(OffsetDateTime.now().getOffset()) * 1000)));
        }

        return baseTime;
    }
    
    public boolean isHasHadPlayers() {
        return hasHadPlayers;
    }
    
    public void setHasHadPlayers(boolean hasHadPlayers) {
        this.hasHadPlayers = hasHadPlayers;
    }
    
    public Boolean getIsActive() {
        return isActive;
    }
    
    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }
}