/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.bo;

import com.soapboxrace.core.dao.OnlineUsersDAO;
import com.soapboxrace.core.dao.UserDAO;
import com.soapboxrace.core.jpa.OnlineUsersEntity;
import com.soapboxrace.core.xmpp.OpenFireRestApiCli;

import javax.ejb.ConcurrencyManagement;
import javax.ejb.ConcurrencyManagementType;
import javax.ejb.Lock;
import javax.ejb.LockType;
import javax.ejb.Schedule;
import javax.ejb.Singleton;
import javax.inject.Inject;
import java.util.Date;

@Singleton
@ConcurrencyManagement(ConcurrencyManagementType.CONTAINER)
public class OnlineUsersBO {

    @Inject
    private OpenFireRestApiCli openFireRestApiCli;

    @Inject
    private OnlineUsersDAO onlineUsersDAO;

    @Inject
    private UserDAO userDAO;

    private volatile OnlineUsersEntity lastRecordedStats;

    @Lock(LockType.READ)
    public OnlineUsersEntity getOnlineUsersStats() {
        if (lastRecordedStats == null) {
            OnlineUsersEntity empty = new OnlineUsersEntity();
            empty.setNumberOfOnline(0);
            empty.setNumberOfRegistered(0);
            return empty;
        }
        return lastRecordedStats;
    }

    @Lock(LockType.WRITE)
    @Schedule(minute = "*", hour = "*", persistent = false)
    public void insertOnlineStats() {
        long timeLong = new Date().getTime() / 1000L;
        OnlineUsersEntity onlineUsersEntity = new OnlineUsersEntity();
        onlineUsersEntity.setNumberOfOnline(openFireRestApiCli.getTotalOnlineUsers());
        onlineUsersEntity.setNumberOfRegistered(userDAO.countUsers());
        onlineUsersEntity.setTimeRecord((int) timeLong);
        onlineUsersDAO.insert(onlineUsersEntity);
        lastRecordedStats = onlineUsersEntity;
    }
}
