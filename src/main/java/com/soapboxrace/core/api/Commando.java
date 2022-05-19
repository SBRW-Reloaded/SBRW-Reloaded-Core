package com.soapboxrace.core.api;

import com.soapboxrace.core.bo.*;
import com.soapboxrace.core.dao.*;
import com.soapboxrace.core.jpa.*;
import com.soapboxrace.core.xmpp.*;

import com.soapboxrace.core.bo.commands.*;

import javax.ejb.EJB;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import java.security.MessageDigest;

@Path("/ofcmdhook")
public class Commando {
    @EJB 
    private ParameterBO parameterBO;

    @EJB 
    private PersonaDAO personaDAO;

    @EJB 
    private AdminBO adminBO;

    @EJB 
    private TokenSessionBO tokenSessionBO;

    @EJB 
    private OpenFireSoapBoxCli openFireSoapBoxCli;

    @EJB 
    private LobbyDAO lobbyDAO;

    @EJB 
    private LobbyEntrantDAO lobbyEntrantDAO;

    @POST
    public Response openfireHook(@HeaderParam("Authorization") String token, @QueryParam("cmd") String command, @QueryParam("pid") long persona, @QueryParam("webhook") Boolean webHook) {        
        //Verify the token first
        String correctToken = parameterBO.getStrParam("OPENFIRE_TOKEN");
        if (token == null || !MessageDigest.isEqual(token.getBytes(), correctToken.getBytes())) {
            return Response.status(Response.Status.BAD_REQUEST).entity("invalid token").build();
        }

        //Find out the user that called the command
        PersonaEntity personaEntity = personaDAO.find(persona);
        
        //Remove slash at the beginning
        command = command.substring(1);

        //Split up commands
        String[] commandSplitted = command.split(" ");

        //print out the command executed info
        if(parameterBO.getBoolParam("SBRWR_ENABLEDEBUG")) {
            openFireSoapBoxCli.send(XmppChat.createSystemMessage("Command executed: " + commandSplitted[0]), personaEntity.getPersonaId());
            openFireSoapBoxCli.send(XmppChat.createSystemMessage("Params: " + command.replace(commandSplitted[0], "").trim()), personaEntity.getPersonaId());
        }

        //Switch between them
        switch(commandSplitted[0].trim()) {
            case "nopu":        new NoPowerups().Command(tokenSessionBO, parameterBO, personaEntity, lobbyDAO, openFireSoapBoxCli, lobbyEntrantDAO); break;
            case "debug":       new Debug().Commands(); break;
            case "ban":         //adopted from below
            case "kick":        //adopted from below
            case "unban":       new AdminCommand().Commands(adminBO, personaEntity, command, webHook, openFireSoapBoxCli); break;
            default:            new DefaultCommand().Command(openFireSoapBoxCli, personaEntity, commandSplitted[0].trim()); break;
        }
        return Response.noContent().build();
    }
}
