/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.bo;

import com.soapboxrace.core.bo.util.TimeConverter;
import com.soapboxrace.core.bo.util.HelpingTools;
import com.soapboxrace.core.bo.util.KonamiDecode;
import com.soapboxrace.core.dao.CarDAO;
import com.soapboxrace.core.jpa.CarEntity;
import com.soapboxrace.core.jpa.EventDataEntity;
import com.soapboxrace.core.jpa.EventSessionEntity;
import com.soapboxrace.jaxb.http.ArbitrationPacket;
import com.soapboxrace.jaxb.http.PursuitArbitrationPacket;
import com.soapboxrace.jaxb.http.TeamEscapeArbitrationPacket;

import javax.ejb.EJB;
import javax.ejb.Stateless;

@Stateless
public class LegitRaceBO {

    @EJB
    private SocialBO socialBo;

    @EJB
    private CarDAO carDAO;

    @EJB
    private ParameterBO parameterBO;

    private void sendReport(String message, Long activePersonaId, ArbitrationPacket arbitrationPacket) {
        socialBo.sendReport(0L, activePersonaId, 4, message, (int) arbitrationPacket.getCarId(), 0, arbitrationPacket.getHacksDetected());
    }

    public boolean isLegit(Long activePersonaId, ArbitrationPacket arbitrationPacket, EventSessionEntity sessionEntity, EventDataEntity dataEntity) {
        long minimumTime = sessionEntity.getEvent().getLegitTime();
        boolean legit = dataEntity.getServerTimeInMilliseconds() >= minimumTime;
        String eventName = HelpingTools.upperFirst(sessionEntity.getEvent().getName().split("\\(")[0].trim());
        String reportMessage = "";

        if (!legit) {
            reportMessage = String.format("Abnormal event time: %d (below minimum of %d on event %d; session %d)", 
                dataEntity.getServerTimeInMilliseconds(), minimumTime, sessionEntity.getEvent().getId(), sessionEntity.getId());

            sendReport(reportMessage, activePersonaId, arbitrationPacket);
            return false;
        }

        //Calculate globaltime
        if((arbitrationPacket.getAlternateEventDurationInMilliseconds()-dataEntity.getServerTimeInMilliseconds()) >= parameterBO.getIntParam("SBRWR_TIME_THRESHOLD", 10000)) {
            int timediff = (int)(arbitrationPacket.getAlternateEventDurationInMilliseconds()-dataEntity.getServerTimeInMilliseconds())/1000;

            reportMessage = String.format("Autofinish detected: timediff is %s (on event %s; session %d)", 
                TimeConverter.secToTime(timediff), eventName, sessionEntity.getId());

            sendReport(reportMessage, activePersonaId, arbitrationPacket);
            return false;
        }

        if (arbitrationPacket.getKonami() > 0) {
            reportMessage = String.format("konami => %s (event %s; session %d)",
                KonamiDecode.getHacksType(arbitrationPacket.getKonami(), "konami"), eventName, sessionEntity.getId());

            sendReport(reportMessage, activePersonaId, arbitrationPacket);
            return false;
        }

        if (arbitrationPacket.getHacksDetected() > 0) {
            reportMessage = String.format("hacksDetected => %s (event %s; session %d)",
                KonamiDecode.getHacksType((int)(arbitrationPacket.getHacksDetected()), "hacksDetected"), eventName, sessionEntity.getId());
    
            sendReport(reportMessage, activePersonaId, arbitrationPacket);
            return false;
        }

        if (arbitrationPacket instanceof TeamEscapeArbitrationPacket) {
            TeamEscapeArbitrationPacket teamEscapeArbitrationPacket = (TeamEscapeArbitrationPacket) arbitrationPacket;

            if (teamEscapeArbitrationPacket.getFinishReason() != 8202) {
                if(teamEscapeArbitrationPacket.getCopsDisabled() > teamEscapeArbitrationPacket.getCopsDeployed()) {
                    reportMessage = String.format("Disabled more cops than deployed (deployed %d; disabled %d)",
                        teamEscapeArbitrationPacket.getCopsDisabled(), teamEscapeArbitrationPacket.getCopsDeployed());
                    
                    sendReport(reportMessage, activePersonaId, arbitrationPacket);
                    return false;
                }
            }
        }

        if (arbitrationPacket instanceof PursuitArbitrationPacket) {
            PursuitArbitrationPacket pursuitArbitrationPacket = (PursuitArbitrationPacket) arbitrationPacket;

            if (pursuitArbitrationPacket.getFinishReason() != 8202) {
                if (pursuitArbitrationPacket.getCopsDisabled() > pursuitArbitrationPacket.getCopsDeployed()) {
                    reportMessage = String.format("Disabled more cops than deployed (deployed %d; disabled %d)",
                        pursuitArbitrationPacket.getCopsDisabled(), pursuitArbitrationPacket.getCopsDeployed());
                
                    sendReport(reportMessage, activePersonaId, arbitrationPacket);
                    return false;                   
                }

                //Calc, wow
                if ((pursuitArbitrationPacket.getAlternateEventDurationInMilliseconds()/1000)/pursuitArbitrationPacket.getCopsDeployed() >= parameterBO.getIntParam("SBRWR_COPS_THRESHOLD", 25)) {
                    reportMessage = String.format("Invalid data received from pursuit outrun, over %d cops in %d seconds",
                        pursuitArbitrationPacket.getCopsDeployed(), pursuitArbitrationPacket.getAlternateEventDurationInMilliseconds()/1000);

                    sendReport(reportMessage, activePersonaId, arbitrationPacket);
                    return false;       
                }

                if(pursuitArbitrationPacket.getTopSpeed() == 0) {
                    reportMessage = "User hasn't moved from place";

                    sendReport(reportMessage, activePersonaId, arbitrationPacket);
                    return false;     
                }

                if(pursuitArbitrationPacket.getInfractions() != 0) {
                    reportMessage = "User didn't made any infraction";

                    sendReport(reportMessage, activePersonaId, arbitrationPacket);
                    return false;     
                }
            }
        }

        CarEntity carEntity = carDAO.find(arbitrationPacket.getCarId());
        if (carEntity == null) {
            reportMessage = "User drived a car that doesn't exists on database.";

            sendReport(reportMessage, activePersonaId, arbitrationPacket);
            return false;    
        }

        if (carEntity.getCarClassHash() == 0) {
            reportMessage = "User drived a car that is traffic or nonexistent.";

            sendReport(reportMessage, activePersonaId, arbitrationPacket);
            return false;    
        }

        if(sessionEntity.getEvent().getCarClassHash() == 607077938 && carEntity.getCarClassHash() != sessionEntity.getEvent().getCarClassHash()) {
            reportMessage = String.format("User drived a car that doesn't meet the class restriction of the event (class %s, eventname %s).", 
                HelpingTools.getClass(carEntity.getCarClassHash()), eventName);

            sendReport(reportMessage, activePersonaId, arbitrationPacket);
            return false;  
        }

        return true;
    }
}
