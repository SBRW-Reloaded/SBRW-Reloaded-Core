package com.soapboxrace.core.api;

import com.soapboxrace.core.api.util.Secured;
import com.soapboxrace.core.bo.RequestSessionInfo;
import com.soapboxrace.core.dao.UserDAO;
import com.soapboxrace.core.jpa.UserEntity;
import com.soapboxrace.jaxb.http.SocialSettings;

import javax.ejb.EJB;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.logging.Logger;

@Path("/debugsocialsettings")
public class DebugSocialSettings {

    private static final Logger LOGGER = Logger.getLogger(DebugSocialSettings.class.getName());

    @Inject
    RequestSessionInfo requestSessionInfo;
    
    @EJB
    UserDAO userDAO;

    @GET
    @Secured
    @Produces(MediaType.APPLICATION_XML)
    public SocialSettings debugSocialSettings() {
        UserEntity userEntityFromSession = requestSessionInfo.getUser();
        Long userId = userEntityFromSession.getId();
        
        // Debug social settings
        LOGGER.info("User ID from session: " + userId);
        LOGGER.info("User email from session: " + userEntityFromSession.getEmail());
        
        // Get user directly from database to compare
        UserEntity userEntityFromDB = userDAO.find(userId);
        
        LOGGER.info("Values from session entity:");
        LOGGER.info("  appearOffline: " + userEntityFromSession.isAppearOffline());
        LOGGER.info("  declineGroupInvite: " + userEntityFromSession.getDeclineGroupInvite());
        LOGGER.info("  declineIncommingFriendRequests: " + userEntityFromSession.isDeclineIncommingFriendRequests());
        LOGGER.info("  declinePrivateInvite: " + userEntityFromSession.getDeclinePrivateInvite());
        LOGGER.info("  hideOfflineFriends: " + userEntityFromSession.isHideOfflineFriends());
        LOGGER.info("  showNewsOnSignIn: " + userEntityFromSession.isShowNewsOnSignIn());
        LOGGER.info("  showOnlyPlayersInSameChatChannel: " + userEntityFromSession.isShowOnlyPlayersInSameChatChannel());
        
        LOGGER.info("Values from fresh DB query:");
        LOGGER.info("  appearOffline: " + userEntityFromDB.isAppearOffline());
        LOGGER.info("  declineGroupInvite: " + userEntityFromDB.getDeclineGroupInvite());
        LOGGER.info("  declineIncommingFriendRequests: " + userEntityFromDB.isDeclineIncommingFriendRequests());
        LOGGER.info("  declinePrivateInvite: " + userEntityFromDB.getDeclinePrivateInvite());
        LOGGER.info("  hideOfflineFriends: " + userEntityFromDB.isHideOfflineFriends());
        LOGGER.info("  showNewsOnSignIn: " + userEntityFromDB.isShowNewsOnSignIn());
        LOGGER.info("  showOnlyPlayersInSameChatChannel: " + userEntityFromDB.isShowOnlyPlayersInSameChatChannel());
        
        // Check if entities are the same instance
        LOGGER.info("Same entity instance? " + (userEntityFromSession == userEntityFromDB));
        LOGGER.info("Entity hash codes: session=" + userEntityFromSession.hashCode() + ", db=" + userEntityFromDB.hashCode());
        
        SocialSettings socialSettings = new SocialSettings();
        socialSettings.setAppearOffline(userEntityFromDB.isAppearOffline());
        socialSettings.setDeclineGroupInvite(userEntityFromDB.getDeclineGroupInvite());
        socialSettings.setDeclineIncommingFriendRequests(userEntityFromDB.isDeclineIncommingFriendRequests());
        socialSettings.setDeclinePrivateInvite(userEntityFromDB.getDeclinePrivateInvite());
        socialSettings.setHideOfflineFriends(userEntityFromDB.isHideOfflineFriends());
        socialSettings.setShowNewsOnSignIn(userEntityFromDB.isShowNewsOnSignIn());
        socialSettings.setShowOnlyPlayersInSameChatChannel(userEntityFromDB.isShowOnlyPlayersInSameChatChannel());
        
        return socialSettings;
    }
}