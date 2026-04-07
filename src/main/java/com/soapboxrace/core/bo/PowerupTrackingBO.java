package com.soapboxrace.core.bo;

import com.soapboxrace.core.dao.EventSessionDAO;
import com.soapboxrace.core.dao.PersonaDAO;
import com.soapboxrace.core.dao.UsedPowerupDAO;
import com.soapboxrace.core.jpa.UsedPowerupEntity;

import javax.inject.Inject;
import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;

@ApplicationScoped

@Transactional
public class PowerupTrackingBO {

    @Inject
    private UsedPowerupDAO usedPowerupDAO;

    @Inject
    private EventSessionDAO eventSessionDAO;

    @Inject
    private PersonaDAO personaDAO;

    public void createPowerupRecord(Long eventSessionId, Long activePersonaId, Integer powerupHash) {
        UsedPowerupEntity usedPowerupEntity = new UsedPowerupEntity();
        usedPowerupEntity.setPersonaEntity(personaDAO.find(activePersonaId));
        usedPowerupEntity.setPowerupHash(powerupHash);

        if (eventSessionId != null) {
            usedPowerupEntity.setEventSessionEntity(eventSessionDAO.find(eventSessionId));
        }

        usedPowerupDAO.insert(usedPowerupEntity);
    }
}
