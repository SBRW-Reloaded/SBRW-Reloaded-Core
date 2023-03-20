/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.api;

import com.soapboxrace.core.api.util.Secured;
import com.soapboxrace.jaxb.http.RegionInfo;

import javax.ejb.EJB;
import com.soapboxrace.core.bo.ParameterBO;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

@Path("/getregioninfo")
public class GetRegionInfo {
    @EJB
    private ParameterBO parameterBO;

    @GET
    @Secured
    @Produces(MediaType.APPLICATION_XML)
    public RegionInfo getRegionInfo() {
        RegionInfo regionInfo = new RegionInfo();
        regionInfo.setCountdownProposalInMilliseconds(parameterBO.getIntParam("SBRWR_REGIONINFO_SETCOUNTDOWNPROPOSAL", 3000));
        regionInfo.setDirectConnectTimeoutInMilliseconds(parameterBO.getIntParam("SBRWR_REGIONINFO_SETDIRECTCONNECTIONTIMEOUT", 1000));
        regionInfo.setDropOutTimeInMilliseconds(parameterBO.getIntParam("SBRWR_REGIONINFO_SETDROPOUTTIME", 15000));
        regionInfo.setEventLoadTimeoutInMilliseconds(parameterBO.getIntParam("SBRWR_REGIONINFO_SETEVENTLOADTIMEOUT", 30000));
        regionInfo.setHeartbeatIntervalInMilliseconds(parameterBO.getIntParam("SBRWR_REGIONINFO_SETHEARTBEATINTERVAL", 1000));
        regionInfo.setUdpRelayBandwidthInBps(parameterBO.getIntParam("SBRWR_REGIONINFO_SETUDPRELAYBANDWIDTH", 9600));
        regionInfo.setUdpRelayTimeoutInMilliseconds(parameterBO.getIntParam("SBRWR_REGIONINFO_SETUDPRELAYTIMEOUT", 60000));
        return regionInfo;

    }
}
