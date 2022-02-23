/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.bo;

import com.soapboxrace.core.bo.util.TimeConverter;
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

    public boolean isLegit(Long activePersonaId, ArbitrationPacket arbitrationPacket,
                           EventSessionEntity sessionEntity,
                           EventDataEntity dataEntity) {
        long minimumTime = sessionEntity.getEvent().getLegitTime();
        boolean legit = dataEntity.getServerTimeInMilliseconds() >= minimumTime;

        if (!legit) {
            socialBo.sendReport(0L, activePersonaId, 4,
                String.format("Abnormal event time: %d (below minimum of %d on event %d; session %d)",
                    dataEntity.getServerTimeInMilliseconds(), minimumTime, sessionEntity.getEvent().getId(), sessionEntity.getId()),
                (int) arbitrationPacket.getCarId(), 0, arbitrationPacket.getHacksDetected());
            return false;
        }

        //Calculate globaltime
        if((arbitrationPacket.getAlternateEventDurationInMilliseconds()-dataEntity.getServerTimeInMilliseconds()) >= parameterBO.getIntParam("SBRWR_TIME_THRESHOLD", 10000)) {
            int timediff = (int)(arbitrationPacket.getAlternateEventDurationInMilliseconds()-dataEntity.getServerTimeInMilliseconds())/1000;

            socialBo.sendReport(0L, activePersonaId, 4,
                String.format("Autofinish detected: timediff is %s (on event %s; session %d)", 
                    TimeConverter.secToTime(timediff), sessionEntity.getEvent().getName().split("\\(")[0].trim(), sessionEntity.getId()),
                (int) arbitrationPacket.getCarId(), 0, arbitrationPacket.getHacksDetected());
            return false;
        }

        if (arbitrationPacket.getHacksDetected() > 0) {
            socialBo.sendReport(0L, activePersonaId, 4,
                    String.format("hacksDetected=%d (event %s; session %d)",
                            arbitrationPacket.getHacksDetected(), sessionEntity.getEvent().getName().split("\\(")[0].trim(), sessionEntity.getId()),
                    (int) arbitrationPacket.getCarId(), 0, arbitrationPacket.getHacksDetected());
            return false;
        }

        if (arbitrationPacket.getKonami() > 0) {
            socialBo.sendReport(0L, activePersonaId, 4,
                    String.format("konami=%d (event %s; session %d)",
                            arbitrationPacket.getKonami(), sessionEntity.getEvent().getName().split("\\(")[0].trim(), sessionEntity.getId()),
                    (int) arbitrationPacket.getCarId(), 0, arbitrationPacket.getHacksDetected());
            return false;
        }

        if (arbitrationPacket instanceof TeamEscapeArbitrationPacket) {
            TeamEscapeArbitrationPacket teamEscapeArbitrationPacket = (TeamEscapeArbitrationPacket) arbitrationPacket;

            if (teamEscapeArbitrationPacket.getFinishReason() != 8202) {
                if(teamEscapeArbitrationPacket.getCopsDisabled() > teamEscapeArbitrationPacket.getCopsDeployed()) {
                    socialBo.sendReport(0L, activePersonaId, 4,
                        String.format("Disabled more cops than deployed (deployed %d; disabled %d)",
                            teamEscapeArbitrationPacket.getCopsDisabled(), teamEscapeArbitrationPacket.getCopsDeployed()),
                    (int) arbitrationPacket.getCarId(), 0, arbitrationPacket.getHacksDetected());

                    return false;
                }
            }
        }

        if (arbitrationPacket instanceof PursuitArbitrationPacket) {
            PursuitArbitrationPacket pursuitArbitrationPacket = (PursuitArbitrationPacket) arbitrationPacket;

            if (pursuitArbitrationPacket.getFinishReason() != 8202) {
                if (pursuitArbitrationPacket.getCopsDisabled() > pursuitArbitrationPacket.getCopsDeployed()) {
                    socialBo.sendReport(0L, activePersonaId, 4,
                        String.format("Disabled more cops than deployed (deployed %d; disabled %d)",
                            pursuitArbitrationPacket.getCopsDisabled(), pursuitArbitrationPacket.getCopsDeployed()),
                    (int) arbitrationPacket.getCarId(), 0, arbitrationPacket.getHacksDetected());

                    return false;                    
                }

                //Calc, wow
                if ((pursuitArbitrationPacket.getAlternateEventDurationInMilliseconds()/1000)/pursuitArbitrationPacket.getCopsDeployed() >= parameterBO.getIntParam("SBRWR_COPS_THRESHOLD", 25)) {
                    socialBo.sendReport(0L, activePersonaId, 4,
                    String.format("Invalid data received from pursuit outrun, over %d cops in %d seconds",
                            pursuitArbitrationPacket.getCopsDeployed(), pursuitArbitrationPacket.getAlternateEventDurationInMilliseconds()/1000),
                    (int) arbitrationPacket.getCarId(), 0, arbitrationPacket.getHacksDetected());

                    return false;       
                }

                return pursuitArbitrationPacket.getTopSpeed() != 0 || pursuitArbitrationPacket.getInfractions() == 0;
            }
        }

        CarEntity carEntity = carDAO.find(arbitrationPacket.getCarId());
        if (carEntity == null) {
            return false;
        }

        return (sessionEntity.getEvent().getCarClassHash() != 607077938 && carEntity.getCarClassHash() == sessionEntity.getEvent().getCarClassHash()) || carEntity.getCarClassHash() != 0;
    }
}
