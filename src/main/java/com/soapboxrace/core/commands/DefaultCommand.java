package com.soapboxrace.core.commands;

import javax.ws.rs.core.Response;

import com.soapboxrace.core.jpa.*;
import com.soapboxrace.core.xmpp.*;

public class DefaultCommand {
    public Response Command(OpenFireSoapBoxCli openFireSoapBoxCli, PersonaEntity personaEntity, String command) {
        openFireSoapBoxCli.send(XmppChat.createSystemMessage("Unknown command: " + command), personaEntity.getPersonaId());
        return Response.noContent().build();
	}
}