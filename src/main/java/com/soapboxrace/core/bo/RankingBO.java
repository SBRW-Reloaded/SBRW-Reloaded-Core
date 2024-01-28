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
            PersonaEntity personaEntity = personaDAO.find(activePersonaId);

            int position = dataEntity.getRank();
            int ranking_points_earned = 0;
            int current_ranking_points = personaEntity.getRankingPoints();
            int calculated_ranking_points;

            switch(position) {
                case 1:
                    ranking_points_earned = parameterBO.getIntParam("SBRWR_RANKEDMODE_POINTS_1", 14);
                    break;
                case 2:
                    ranking_points_earned = parameterBO.getIntParam("SBRWR_RANKEDMODE_POINTS_2", 6);
                    break;
                case 3:
                    ranking_points_earned = parameterBO.getIntParam("SBRWR_RANKEDMODE_POINTS_3", -4);
                    break;
                case 4:
                    ranking_points_earned = parameterBO.getIntParam("SBRWR_RANKEDMODE_POINTS_4", -10);
                    break;
            }

            calculated_ranking_points = Math.max(current_ranking_points + ranking_points_earned, 0);
            if(calculated_ranking_points == 0) ranking_points_earned = 0;

            String rankingMessage = String.format("SBRWR_RANKEDMODE_POS%s,%s,%s", position, ranking_points_earned, calculated_ranking_points);

            openFireSoapBoxCli.send(XmppChat.createSystemMessage(rankingMessage), activePersonaId);
        }
    }
}
