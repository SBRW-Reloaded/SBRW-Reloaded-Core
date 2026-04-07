package com.soapboxrace.core.api;

import com.soapboxrace.core.bo.*;
import com.soapboxrace.core.commands.*;
import com.soapboxrace.core.dao.*;
import com.soapboxrace.core.jpa.*;
import com.soapboxrace.core.xmpp.*;

import javax.inject.Inject;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import java.security.MessageDigest;

@Path("/ofcmdhook")
public class Commando {
    @Inject 
    private ParameterBO parameterBO;

    @Inject 
    private PersonaDAO personaDAO;

    @Inject 
    private AdminBO adminBO;

    @Inject 
    private PersonaBO personaBO;

    @Inject 
    private TokenSessionBO tokenSessionBO;

    @Inject 
    private OpenFireSoapBoxCli openFireSoapBoxCli;

    @Inject 
    private LobbyDAO lobbyDAO;

    @Inject 
    private LobbyEntrantDAO lobbyEntrantDAO;

    @Inject
    private LiveryStoreDAO liveryStoreDao;

    @Inject
    private VinylDAO vinylDao;

    @Inject
    private VinylProductDAO vinylProductDAO;

    @Inject
    private LiveryStoreDataDAO liveryStoreDataDao;

    @Inject
    private LobbyCountdownBO lobbyCountdownBO;

    @Inject
    private CarDAO carDAO;

    @Inject
    private AutoTuneBO autoTuneBO;

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
        command = command.replace("/", "");

        //Split up commands
        String[] commandSplitted = command.split(" ");

        //print out the command executed info
        if(parameterBO.getBoolParam("SBRWR_ENABLEDEBUG")) {
            openFireSoapBoxCli.send(XmppChat.createSystemMessage("Command executed: " + commandSplitted[0]), personaEntity.getPersonaId());
            openFireSoapBoxCli.send(XmppChat.createSystemMessage("Params: " + command.replace(commandSplitted[0], "").trim()), personaEntity.getPersonaId());
        }

        //Switch between them
        switch(commandSplitted[0].trim()) {
            case "r":           //short alias for ready
            case "ready":       new ReadyCommand().Command(tokenSessionBO, parameterBO, personaEntity, lobbyDAO, openFireSoapBoxCli, lobbyCountdownBO); break;
            case "nopu":        new NoPowerups().Command(tokenSessionBO, parameterBO, personaEntity, lobbyDAO, openFireSoapBoxCli, lobbyEntrantDAO); break;
            case "debug":       new Debug().Commands(); break;
            case "ban":         //adopted from below
            case "kick":        //adopted from below
            case "unban":       new AdminCommand().Command(adminBO, personaEntity, command, webHook, openFireSoapBoxCli); break;
            case "livery":      new LiveryCommand().Command(command, openFireSoapBoxCli, personaEntity, liveryStoreDao, vinylDao, liveryStoreDataDao, parameterBO, personaBO, vinylProductDAO); break;
            case "carid":       new CarIdCommand().Command(openFireSoapBoxCli, personaEntity, personaBO); break;
            case "tune":
                if (parameterBO.getBoolParam("SBRWR_ENABLE_AUTOTUNE")) {
                    TokenSessionEntity tuneTokenData = tokenSessionBO.findByUserId(personaEntity.getUser().getId());
                    if (tuneTokenData != null && tuneTokenData.isInSafehouse()) {
                        openFireSoapBoxCli.send(XmppChat.createSystemMessage("AutoTune can only be used in freeroam."), personaEntity.getPersonaId());
                    } else {
                        new AutoTuneCommand().Command(command, openFireSoapBoxCli, personaEntity, autoTuneBO, tokenSessionBO);
                    }
                } else {
                    openFireSoapBoxCli.send(XmppChat.createSystemMessage("AutoTune is disabled."), personaEntity.getPersonaId());
                }
                break;
            default:            new DefaultCommand().Command(openFireSoapBoxCli, personaEntity, commandSplitted[0].trim()); break;
        }
        return Response.noContent().build();
    }
}
