/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.api;

import com.soapboxrace.core.api.util.Secured;
import com.soapboxrace.core.bo.RequestSessionInfo;
import com.soapboxrace.core.jpa.UserEntity;
import com.soapboxrace.jaxb.http.SocialSettings;

import javax.inject.Inject;
import javax.ws.rs.GET;
import java.util.logging.Logger;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

@Path("/getsocialsettings")
public class GetSocialSettings {

    private static final Logger LOGGER = Logger.getLogger(GetSocialSettings.class.getName());

    @Inject
    RequestSessionInfo requestSessionInfo;

    @GET
    @Secured
    @Produces(MediaType.APPLICATION_XML)
    public SocialSettings getSocialSettings() {
        UserEntity userEntity = requestSessionInfo.getUser();
        
        SocialSettings socialSettings = new SocialSettings();
        socialSettings.setAppearOffline(userEntity.isAppearOffline());
        socialSettings.setDeclineGroupInvite(userEntity.getDeclineGroupInvite());
        socialSettings.setDeclineIncommingFriendRequests(userEntity.isDeclineIncommingFriendRequests());
        socialSettings.setDeclinePrivateInvite(userEntity.getDeclinePrivateInvite());
        socialSettings.setHideOfflineFriends(userEntity.isHideOfflineFriends());
        socialSettings.setShowNewsOnSignIn(userEntity.isShowNewsOnSignIn());
        socialSettings.setShowOnlyPlayersInSameChatChannel(userEntity.isShowOnlyPlayersInSameChatChannel());
        return socialSettings;
    }
}
