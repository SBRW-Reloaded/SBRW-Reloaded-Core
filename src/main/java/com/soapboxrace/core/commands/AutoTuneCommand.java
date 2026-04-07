package com.soapboxrace.core.commands;

import com.soapboxrace.core.bo.AutoTuneBO;
import com.soapboxrace.core.jpa.PersonaEntity;
import com.soapboxrace.core.xmpp.OpenFireSoapBoxCli;

import com.soapboxrace.core.bo.TokenSessionBO;
import com.soapboxrace.core.jpa.TokenSessionEntity;

import javax.ws.rs.core.Response;

public class AutoTuneCommand {
    public Response Command(String command, OpenFireSoapBoxCli openFireSoapBoxCli, PersonaEntity personaEntity, AutoTuneBO autoTuneBO, TokenSessionBO tokenSessionBO) {
        String[] args = command.split(" ");
        TokenSessionEntity tokenData = tokenSessionBO.findByUserId(personaEntity.getUser().getId());
        autoTuneBO.processTuneCommand(args, personaEntity, openFireSoapBoxCli);
        return Response.noContent().build();
    }
}
