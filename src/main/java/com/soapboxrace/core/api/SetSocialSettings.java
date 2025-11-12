/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.api;

import com.soapboxrace.core.api.util.Secured;
import com.soapboxrace.core.bo.PresenceBO;
import com.soapboxrace.core.bo.RequestSessionInfo;
import com.soapboxrace.core.dao.UserDAO;
import com.soapboxrace.core.jpa.UserEntity;
import com.soapboxrace.jaxb.http.SocialSettings;
import com.soapboxrace.jaxb.util.JAXBUtility;
import org.eclipse.microprofile.metrics.annotation.Timed;

import javax.ejb.EJB;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.io.InputStream;

@Path("/setsocialsettings")
@Timed
public class SetSocialSettings {

    @Inject
    RequestSessionInfo requestSessionInfo;

    @EJB
    UserDAO userDAO;

    @EJB
    PresenceBO presenceBO;

    @PUT
    @Transactional
    @Secured
    @Produces(MediaType.APPLICATION_XML)
    public String setSocialSettings(InputStream inputStream) {
        SocialSettings socialSettings = JAXBUtility.unMarshal(inputStream, SocialSettings.class);
        UserEntity userEntity = requestSessionInfo.getUser();

        userEntity.setAppearOffline(socialSettings.isAppearOffline());
        userEntity.setDeclineGroupInvite(socialSettings.getDeclineGroupInvite());
        userEntity.setDeclineIncommingFriendRequests(socialSettings.isDeclineIncommingFriendRequests());
        userEntity.setDeclinePrivateInvite(socialSettings.getDeclinePrivateInvite());
        userEntity.setHideOfflineFriends(socialSettings.isHideOfflineFriends());
        userEntity.setShowNewsOnSignIn(socialSettings.isShowNewsOnSignIn());
        userEntity.setShowOnlyPlayersInSameChatChannel(socialSettings.isShowOnlyPlayersInSameChatChannel());

        if (userEntity.isAppearOffline()) {
            final Long activePersonaId = requestSessionInfo.getActivePersonaId();
            if (activePersonaId != null) {
                presenceBO.updatePresence(activePersonaId, PresenceBO.PRESENCE_OFFLINE);
            }
        }

        // Use targeted update instead of full entity update to avoid overwriting other fields
        userDAO.updateSocialSettings(userEntity.getId(),
                socialSettings.isAppearOffline(),
                socialSettings.getDeclineGroupInvite(),
                socialSettings.isDeclineIncommingFriendRequests(),
                socialSettings.getDeclinePrivateInvite(),
                socialSettings.isHideOfflineFriends(),
                socialSettings.isShowNewsOnSignIn(),
                socialSettings.isShowOnlyPlayersInSameChatChannel());

        return "";
    }
}