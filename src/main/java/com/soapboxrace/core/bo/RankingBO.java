package com.soapboxrace.core.bo;

import javax.ejb.EJB;
import javax.ejb.Stateless;

import com.soapboxrace.core.dao.BanDAO;
import com.soapboxrace.core.dao.EventDataDAO;
import com.soapboxrace.core.dao.PersonaDAO;
import com.soapboxrace.core.jpa.EventDataEntity;
import com.soapboxrace.core.jpa.PersonaEntity;
import com.soapboxrace.core.xmpp.OpenFireSoapBoxCli;
import com.soapboxrace.core.xmpp.XmppChat;

@Stateless
public class RankingBO {
    @EJB
    private OpenFireSoapBoxCli openFireSoapBoxCli;

    @EJB
    private ParameterBO parameterBO;
    
    @EJB
    private EventDataDAO eventDataDAO;

    @EJB 
    private PersonaDAO personaDAO;
    
    @EJB 
    private BanDAO banDAO;

    public void setupRanking(Long activePersonaId, EventDataEntity dataEntity) {
        if(dataEntity.getEvent().isRankedMode()) {
            int ranking = dataEntity.getRank();
            PersonaEntity personaEntity = personaDAO.find(activePersonaId);

            if(ranking == 1) openFireSoapBoxCli.send(XmppChat.createSystemMessage(String.format("SBRWR_RANKEDMODE_POS1,%s,%s", "10", personaEntity.getRankingPoints()+10)), activePersonaId);
            if(ranking == 2) openFireSoapBoxCli.send(XmppChat.createSystemMessage(String.format("SBRWR_RANKEDMODE_POS2,%s,%s", "4", personaEntity.getRankingPoints()+4)), activePersonaId);
            if(ranking == 3) openFireSoapBoxCli.send(XmppChat.createSystemMessage(String.format("SBRWR_RANKEDMODE_POS3,%s,%s", "-4", personaEntity.getRankingPoints()-4)), activePersonaId);
            if(ranking == 4) openFireSoapBoxCli.send(XmppChat.createSystemMessage(String.format("SBRWR_RANKEDMODE_POS4,%s,%s", "-10", personaEntity.getRankingPoints()-10)), activePersonaId);
        }
    }
}
