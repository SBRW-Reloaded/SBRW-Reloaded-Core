package com.soapboxrace.core.api;

import com.soapboxrace.core.api.util.Secured;
import com.soapboxrace.core.dao.UserDAO;
import com.soapboxrace.core.jpa.UserEntity;

import javax.ejb.EJB;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.logging.Logger;

@Path("/testsql")
public class TestSqlValues {

    private static final Logger LOGGER = Logger.getLogger(TestSqlValues.class.getName());

    @EJB
    UserDAO userDAO;

    @GET
    @Secured
    @Produces(MediaType.TEXT_PLAIN)
    @Path("/{userId}")
    public String testSqlValues(@PathParam("userId") Long userId) {
        LOGGER.info("Testing SQL values for user ID: " + userId);
        
        // Force refresh from database
        UserEntity userEntity = userDAO.find(userId);
        
        StringBuilder result = new StringBuilder();
        result.append("User ID: ").append(userEntity.getId()).append("\n");
        result.append("Email: ").append(userEntity.getEmail()).append("\n");
        result.append("State: ").append(userEntity.getState()).append("\n");
        result.append("appearOffline: ").append(userEntity.isAppearOffline()).append("\n");
        result.append("declineGroupInvite: ").append(userEntity.getDeclineGroupInvite()).append("\n");
        result.append("declineIncommingFriendRequests: ").append(userEntity.isDeclineIncommingFriendRequests()).append("\n");
        result.append("declinePrivateInvite: ").append(userEntity.getDeclinePrivateInvite()).append("\n");
        result.append("hideOfflineFriends: ").append(userEntity.isHideOfflineFriends()).append("\n");
        result.append("showNewsOnSignIn: ").append(userEntity.isShowNewsOnSignIn()).append("\n");
        result.append("showOnlyPlayersInSameChatChannel: ").append(userEntity.isShowOnlyPlayersInSameChatChannel()).append("\n");
        
        return result.toString();
    }
    
    @GET
    @Secured
    @Produces(MediaType.TEXT_PLAIN)
    @Path("/set/{userId}")
    public String setSqlValues(@PathParam("userId") Long userId) {
        LOGGER.info("Setting test values for user ID: " + userId);
        
        UserEntity userEntity = userDAO.find(userId);
        
        // Set test values
        userEntity.setAppearOffline(true);
        userEntity.setDeclineGroupInvite(2);
        userEntity.setDeclineIncommingFriendRequests(true);
        userEntity.setDeclinePrivateInvite(1);
        userEntity.setHideOfflineFriends(true);
        userEntity.setShowNewsOnSignIn(true);
        userEntity.setShowOnlyPlayersInSameChatChannel(false);
        
        userDAO.update(userEntity);
        
        return "Test values set for user " + userId;
    }
}