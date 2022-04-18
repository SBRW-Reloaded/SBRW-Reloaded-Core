package com.soapboxrace.core.bo.util;

public class HelpingTools {
    public static Boolean isNullOrEmptyCheck(String string) {
        if (string == null || string.trim().isEmpty()) { 
            return true;
        }

        return false;
    }

    public static String upperFirst(String string) {
        return string.substring(0, 1).toUpperCase() + string.substring(1).toLowerCase();
    }

    public static String getClass(int classHash) {
        switch(classHash) {
            case 869393278: return "F";
            case 872416321: return "E";
            case 415909161: return "D";
            case 1866825865: return "C";
            case -406473455: return "B";
            case -405837480: return "A";
            case -2142411446: return "S";
            case 86241155: return "S1";
            case 221915816: return "S2";
            case 1526233495: return "S3";
            default: return "OPEN";
        }
    }
}
