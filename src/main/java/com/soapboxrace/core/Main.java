package com.soapboxrace.core;

import javax.ejb.EJB;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import com.soapboxrace.core.bo.ParameterBO;
import com.soapboxrace.core.dao.UserDAO;



@Startup
@Singleton
public class Main {
    @EJB
    private ParameterBO parameterBO;

    @EJB
    private UserDAO userDao;

    @PostConstruct
    public void init() {
        System.setProperty("user.timezone", parameterBO.getStrParam("SBRWR_TIMEZONE", "Europe/Paris"));
        System.out.println("Using timezone: " + parameterBO.getStrParam("SBRWR_TIMEZONE", "Europe/Paris"));

        userDao.updateOnlineState("OFFLINE"); 
    }

    @PreDestroy
	public void terminate() {
        userDao.updateOnlineState("OFFLINE");
	}
}