package com.soapboxrace.core.bo;

import javax.ejb.EJB;

import org.apache.commons.lang3.time.DurationFormatUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.soapboxrace.core.bo.util.HelpingTools;
import com.soapboxrace.core.dao.BanDAO;
import com.soapboxrace.core.dao.EventDataDAO;
import com.soapboxrace.core.dao.PersonaDAO;
import com.soapboxrace.core.jpa.BanEntity;
import com.soapboxrace.core.jpa.EventDataEntity;
import com.soapboxrace.core.jpa.EventSessionEntity;
import com.soapboxrace.core.jpa.PersonaEntity;
import com.soapboxrace.core.xmpp.OpenFireSoapBoxCli;
import com.soapboxrace.core.xmpp.XmppChat;
import com.soapboxrace.jaxb.http.*;

public class LeaderboardBO {
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

    public void setupLeaderboard(Long activePersonaId, ArbitrationPacket arbitrationPacket, EventSessionEntity sessionEntity, EventDataEntity dataEntity) {
        if(parameterBO.getBoolParam("SBRWR_ENABLE_LEADERBOARD")) {
            new java.util.Timer().schedule( 
                new java.util.TimerTask() {
                    @Override
                    public void run() {
                        // Get the query for stats
                        int current_ranking = 1;
                        Long top_player_id = 0L;
                        String top_player_time = null;
                        boolean send_message = true;

                        //Lock certain values
                        if(dataEntity.getBustedCount() != 0) send_message = false;
                        if(dataEntity.getCarClassHash() == 0) send_message = false;

                        if(send_message) {
                            List<EventDataEntity> unsorted_ranking = eventDataDAO.getRankings(dataEntity.getEvent().getId());
                            Map<Long, Long> map = new HashMap<>();

                            map.put(activePersonaId, dataEntity.getEventDurationInMilliseconds());

                            for (EventDataEntity entity : unsorted_ranking) {
                                if(entity.getBustedCount() != 0) continue;
                                if(entity.getCarClassHash() == 0) continue;
                                if(banDAO.findByUser(personaDAO.find(entity.getPersonaId()).getUser()) != null) continue;

                                if(!map.containsKey(entity.getPersonaId())) {
                                    map.put(entity.getPersonaId(), 999999999999999L);
                                }
                                
                                if(map.get(entity.getPersonaId()) >= entity.getEventDurationInMilliseconds()) {
                                    map.put(entity.getPersonaId(), entity.getEventDurationInMilliseconds());
                                }
                            }

                            for (EventDataEntity entity : unsorted_ranking) {
                                if(entity.getBustedCount() != 0) continue;
                                if(entity.getCarClassHash() == 0) continue;
                                if(banDAO.findByUser(personaDAO.find(entity.getPersonaId()).getUser()) != null) continue;

                                //First result is always the top1 player
                                if(top_player_id.equals(0L)) {
                                    top_player_id = entity.getPersonaId();
                                    top_player_time = DurationFormatUtils.formatDurationHMS(entity.getEventDurationInMilliseconds());
                                }
                                continue;
                            }

                            Map<Long, Long> sorted_ranking = HelpingTools.sortByValue(map);

                            for(Entry<Long, Long> pair : sorted_ranking.entrySet()) {
                                if(activePersonaId.equals(pair.getKey())) {   
                                    String time_formatted = DurationFormatUtils.formatDurationHMS(pair.getValue());

                                    openFireSoapBoxCli.send(XmppChat.createSystemMessage(String.format("SBRWR_LEADERBOARD_INFO,%s,%s", current_ranking, time_formatted)), activePersonaId);

                                    //Top stat
                                    PersonaEntity topPersonaEntity = personaDAO.find(top_player_id);
                                    if(topPersonaEntity != null) {
                                        openFireSoapBoxCli.send(XmppChat.createSystemMessage(String.format("SBRWR_LEADERBOARD_TOP_INFO,%s,%s", topPersonaEntity.getName(), top_player_time)), activePersonaId);
                                    }
                                    
                                    continue;
                                }
                                current_ranking++;
                            }
                        }
                    }
                }, (2000)
            );
        }
    }
}
