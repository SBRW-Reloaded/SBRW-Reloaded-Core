package com.soapboxrace.core.bo;
import javax.inject.Inject;
import javax.ejb.Asynchronous;
import javax.ejb.Singleton;
import javax.ejb.Startup;

import io.sentry.Sentry;

import javax.annotation.PostConstruct;

@Startup
@Singleton
public class ErrorReportingBO {

    @Inject
    private ParameterBO parameterBO;

    private boolean sentryEnabled;

    @PostConstruct
    public void init() {
        if (parameterBO.getBoolParam("ENABLE_SENTRY_REPORTING")) {
            String dsn = parameterBO.getStrParam("SENTRY_DSN");
            if (dsn != null && !dsn.isEmpty()) {
                Sentry.init(options -> options.setDsn(dsn));
                sentryEnabled = true;
            }
        }
    }

    @Asynchronous
    public void sendException(Exception exception) {
        if (sentryEnabled) {
            Sentry.captureException(exception);
        }
    }
}
