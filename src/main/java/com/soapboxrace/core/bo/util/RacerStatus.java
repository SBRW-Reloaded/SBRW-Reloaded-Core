package com.soapboxrace.core.bo.util;

public enum RacerStatus {
    UNKNOWN(0),
    IN_RACE(1),
    FINISHED(2),
    ABANDONED(3),
    BUSTED(4);

    private Integer racerStatus;

    private RacerStatus(Integer racerStatus) {
        this.racerStatus = racerStatus;
    }

    public Integer racerStatus() {
        return racerStatus;
    }
}
