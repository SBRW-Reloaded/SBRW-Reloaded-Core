package com.soapboxrace.core.xmpp;
 
import javax.annotation.PostConstruct;
import javax.ejb.EJB;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import java.util.List;
 
@Startup
@Singleton
public class OpenFireRestApiCli {
    private XmppProvider provider;

    @EJB(beanName = "OpenfireXmppProvider")
    XmppProvider openfireProvider;
 
    @EJB(beanName = "SbrwXmppProvider")
    XmppProvider sbrwProvider;
 
     @PostConstruct
     public void init() {
          if (openfireProvider.isEnabled()) {
               provider = openfireProvider;
          } else if (sbrwProvider.isEnabled()) {
               provider = sbrwProvider;
          } else {
               throw new RuntimeException("No XMPP provider is enabled");
          }
     }
 
     public void createUpdatePersona(Long personaId, String password) {
          provider.createPersona(personaId, password);
     }
 
     public int getTotalOnlineUsers() {
          return provider.getOnlineUserCount();
     }
 
     public List<Long> getAllPersonaByGroup(Long personaId) {
          return provider.getAllPersonasInGroup(personaId);
     }
 
     public void sendChatAnnouncement(String message) {
          provider.sendChatAnnouncement(message);
     }
 
     public void sendMessage(long recipient, String message) {
          provider.sendMessage(recipient, message);
     }
}