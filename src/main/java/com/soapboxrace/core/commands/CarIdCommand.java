package com.soapboxrace.core.commands;

import com.soapboxrace.core.bo.*;
import com.soapboxrace.core.jpa.*;
import com.soapboxrace.core.xmpp.*;

import javax.ws.rs.core.Response;

public class CarIdCommand {
    public Response Command(OpenFireSoapBoxCli openFireSoapBoxCli, PersonaEntity personaEntity, PersonaBO personaBO) {
        openFireSoapBoxCli.send(XmppChat.createSystemMessage("CAR ID: " + personaBO.getDefaultCarEntity(personaEntity.getPersonaId()).getId()), personaEntity.getPersonaId());
        return Response.noContent().build();
	}
}
