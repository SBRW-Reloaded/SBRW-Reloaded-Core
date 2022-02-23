/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.bo.util;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.GregorianCalendar;

public class TimeConverter {
    private static final DatatypeFactory DATATYPE_FACTORY;

    static {
        try {
            DATATYPE_FACTORY = DatatypeFactory.newInstance();
        } catch (DatatypeConfigurationException e) {
            throw new RuntimeException(e);
        }
    }

    public static XMLGregorianCalendar generateGregorianCalendar(GregorianCalendar calendar) {
        return DATATYPE_FACTORY.newXMLGregorianCalendar(calendar);
    }

    public static long getTicks() {
        return getTicks(System.currentTimeMillis());
    }

    public static long getTicks(LocalDateTime localDateTime) {
        return getTicks(localDateTime.atZone(ZoneId.systemDefault()).toEpochSecond() * 1000);
    }

    public static long getTicks(long unixTimestampMs) {
        return unixTimestampMs * 10000 + 621355968000000000L;
    }

    public static String secToTime(int sec) {
        int seconds = sec % 60;
        int minutes = sec / 60;

        if(minutes != 0) {
            if (minutes >= 60) {
                int hours = minutes / 60;
                minutes %= 60;

                if(hours >= 24) {
                    int days = hours / 24;
                    return String.format("%d days, %d hours, %d mins and %d secs", days, hours%24, minutes, seconds);
                }

                return String.format("%d hours, %d mins and %d secs", hours, minutes, seconds);
            }

            return String.format("%d mins and %d secs", minutes, seconds);
        }

        return String.format("%d secs", seconds);
    }
}
