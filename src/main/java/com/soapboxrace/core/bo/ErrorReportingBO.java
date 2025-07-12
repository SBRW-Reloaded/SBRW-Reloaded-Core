package com.soapboxrace.core.bo;

import io.sentry.SentryClient;
import io.sentry.SentryClientFactory;

import javax.annotation.PostConstruct;
import javax.ejb.*;

@Startup
@Singleton
public class ErrorReportingBO {

    @EJB
    private ParameterBO parameterBO;

    private SentryClient sentryClient;

    @PostConstruct
    public void init() {
        if (parameterBO.getBoolParam("ENABLE_SENTRY_REPORTING")) {
            this.sentryClient = SentryClientFactory.sentryClient(parameterBO.getStrParam("SENTRY_DSN"));
        }
    }

    @Asynchronous
    @Lock(LockType.READ)
    public void sendException(Exception exception) {
        if (this.sentryClient != null) {
            this.sentryClient.sendException(exception);
        }
    }
}
