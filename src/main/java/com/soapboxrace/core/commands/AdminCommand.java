package com.soapboxrace.core.commands;

import javax.ws.rs.core.Response;

import com.soapboxrace.core.bo.*;
import com.soapboxrace.core.jpa.*;
import com.soapboxrace.core.xmpp.OpenFireSoapBoxCli;
import com.soapboxrace.core.xmpp.XmppChat;

public class AdminCommand {
    public Response Command(AdminBO adminBO, PersonaEntity personaEntity, String command, Boolean webHook, OpenFireSoapBoxCli openFireSoapBoxCli) {
        if (personaEntity.getUser().isAdmin()) {
            Boolean sendOrNot = webHook == null ? true : webHook;
            adminBO.sendChatCommand(personaEntity.getPersonaId(), command, personaEntity.getName(), sendOrNot);
        } else {
            openFireSoapBoxCli.send(XmppChat.createSystemMessage("No Permission to use administrative commands."), personaEntity.getPersonaId());
        }

        return Response.noContent().build();
	}
}