/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.bo;
import javax.inject.Inject;
import javax.ejb.Singleton;
import javax.ejb.Startup;

import com.soapboxrace.core.dao.ParameterDAO;
import com.soapboxrace.core.jpa.ParameterEntity;
import com.soapboxrace.core.jpa.UserEntity;

import javax.annotation.PostConstruct;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

@Startup
@Singleton
public class ParameterBO {

    @Inject
    private ParameterDAO parameterDao;

    private final ConcurrentMap<String, String> parameterMap;

    private static final Map<String, String> DEFAULT_PARAMETERS = new LinkedHashMap<>();
    static {
        // Bool parameters
        DEFAULT_PARAMETERS.put("ACHIEVEMENT_CHAT_SEND", "false");
        DEFAULT_PARAMETERS.put("ENABLE_CAR_DAMAGE", "false");
        DEFAULT_PARAMETERS.put("ENABLE_DROP_ITEM", "false");
        DEFAULT_PARAMETERS.put("ENABLE_ECONOMY", "false");
        DEFAULT_PARAMETERS.put("ENABLE_POWERUP_DECREASE", "false");
        DEFAULT_PARAMETERS.put("ENABLE_REDIS", "false");
        DEFAULT_PARAMETERS.put("ENABLE_REPUTATION", "false");
        DEFAULT_PARAMETERS.put("ENABLE_SENTRY_REPORTING", "false");
        DEFAULT_PARAMETERS.put("ENABLE_TREASURE_HUNT", "false");
        DEFAULT_PARAMETERS.put("ENABLE_WHITELISTED_LAUNCHERS_ONLY", "false");
        DEFAULT_PARAMETERS.put("happyHourEnabled", "false");
        DEFAULT_PARAMETERS.put("IS_MAINTENANCE", "false");
        DEFAULT_PARAMETERS.put("LOBBY_WAIT_FOR_MIN_PLAYERS", "false");
        DEFAULT_PARAMETERS.put("MODERN_AUTH_ENABLED", "false");
        DEFAULT_PARAMETERS.put("MODDING_ENABLED", "false");
        DEFAULT_PARAMETERS.put("SIGNED_LAUNCHER", "false");
        DEFAULT_PARAMETERS.put("SBRWR_ALLOW_ADJACENT_CAR_CLASSES", "false");
        DEFAULT_PARAMETERS.put("SBRWR_BYPASS_MISSING_HASH", "false");
        DEFAULT_PARAMETERS.put("SBRWR_DISABLE_1_REPORTS", "false");
        DEFAULT_PARAMETERS.put("SBRWR_DISABLE_2_REPORTS", "false");
        DEFAULT_PARAMETERS.put("SBRWR_DISABLE_4_REPORTS", "false");
        DEFAULT_PARAMETERS.put("SBRWR_DISABLE_8_REPORTS", "false");
        DEFAULT_PARAMETERS.put("SBRWR_DISABLE_16_REPORTS", "false");
        DEFAULT_PARAMETERS.put("SBRWR_DISABLE_32_REPORTS", "false");
        DEFAULT_PARAMETERS.put("SBRWR_ENABLE_LEADERBOARD", "false");
        DEFAULT_PARAMETERS.put("SBRWR_ENABLE_LOBBY_READY", "false");
        DEFAULT_PARAMETERS.put("SBRWR_ENABLE_NOPU", "false");
        DEFAULT_PARAMETERS.put("SBRWR_ENABLE_AUTOTUNE", "false");
        DEFAULT_PARAMETERS.put("SBRWR_AUTOTUNE_COMMAND_MAX_ITERATIONS", "1000000");
        DEFAULT_PARAMETERS.put("SBRWR_AUTOTUNE_RELOAD_MAX_ITERATIONS", "10000000");
        DEFAULT_PARAMETERS.put("SBRWR_ENABLEDEBUG", "false");
        DEFAULT_PARAMETERS.put("SBRWR_INFORM_EVENT", "false");
        DEFAULT_PARAMETERS.put("SBRWR_KEEP_CARS", "false");
        DEFAULT_PARAMETERS.put("SBRWR_KEEP_PERSONA", "false");
        DEFAULT_PARAMETERS.put("SBRWR_LOCK_LOBBY_CAR_CLASS", "false");
        DEFAULT_PARAMETERS.put("SBRWR_NOPU_ENABLE_VOTEMESSAGES", "false");
        DEFAULT_PARAMETERS.put("SBRWR_NOPU_ENABLE_WARNING_ONEVENT", "false");
        DEFAULT_PARAMETERS.put("SBRWR_NOPU_ENABLE_WARNING_ONFREEROAM", "false");
        DEFAULT_PARAMETERS.put("SBRWR_NOPU_SHOW_NOTENOUGHVOTES", "false");
        DEFAULT_PARAMETERS.put("SBRWR_NR_ENABLECREW", "false");
        DEFAULT_PARAMETERS.put("SBRWR_RACENOW_PERSISTENT_ENABLED", "false");
        DEFAULT_PARAMETERS.put("SBRWR_READY_ENABLE_VOTEMESSAGES", "false");
        DEFAULT_PARAMETERS.put("SBRWR_SEND_ADMIN_ACTION", "false");
        DEFAULT_PARAMETERS.put("SBRWR_TRANSLATABLE", "false");

        // Int parameters
        DEFAULT_PARAMETERS.put("CAR_SLOTS_ADD", "1");
        DEFAULT_PARAMETERS.put("CAR_SLOTS_MAXAMMOUNT", "2000");
        DEFAULT_PARAMETERS.put("LOBBY_COUNTDOWN_TIME", "0");
        DEFAULT_PARAMETERS.put("MAX_ICON_INDEX", "26");
        DEFAULT_PARAMETERS.put("MAX_IP_REGISTRATIONS", "5");
        DEFAULT_PARAMETERS.put("MAX_ONLINE_PLAYERS", "-1");
        DEFAULT_PARAMETERS.put("MAX_PLAYER_CASH_FREE", "9999999");
        DEFAULT_PARAMETERS.put("MAX_PLAYER_CASH_PREMIUM", "9999999");
        DEFAULT_PARAMETERS.put("MAX_PLAYER_LEVEL_FREE", "60");
        DEFAULT_PARAMETERS.put("MAX_PLAYER_LEVEL_PREMIUM", "60");
        DEFAULT_PARAMETERS.put("MAX_PROFILES", "3");
        DEFAULT_PARAMETERS.put("RACE_AGAIN_EMPTY_LOBBY_GRACE_PERIOD", "30000");
        DEFAULT_PARAMETERS.put("REDIS_PORT", "6379");
        DEFAULT_PARAMETERS.put("REWARD_CASH_BASELINE_LEVEL", "0");
        DEFAULT_PARAMETERS.put("REWARD_REP_BASELINE_LEVEL", "0");
        DEFAULT_PARAMETERS.put("SERVER_INFO_SHUTDOWN_TIMER", "7200");
        DEFAULT_PARAMETERS.put("SERVER_INFO_TIMEZONE", "0");
        DEFAULT_PARAMETERS.put("SESSION_HEARTBEAT_TIMEOUT_ACTIVE", "3");
        DEFAULT_PARAMETERS.put("SESSION_HEARTBEAT_TIMEOUT_INACTIVE", "10");
        DEFAULT_PARAMETERS.put("SESSION_LENGTH_MINUTES", "130");
        DEFAULT_PARAMETERS.put("STARTING_BOOST_AMOUNT", "0");
        DEFAULT_PARAMETERS.put("STARTING_CASH_AMOUNT", "0");
        DEFAULT_PARAMETERS.put("STARTING_INVENTORY_PERF_SLOTS", "100");
        DEFAULT_PARAMETERS.put("STARTING_INVENTORY_SKILL_SLOTS", "100");
        DEFAULT_PARAMETERS.put("STARTING_INVENTORY_VISUAL_SLOTS", "100");
        DEFAULT_PARAMETERS.put("STARTING_LEVEL_NUMBER", "1");
        DEFAULT_PARAMETERS.put("SBRWR_COPS_THRESHOLD", "25");
        DEFAULT_PARAMETERS.put("SBRWR_INFORM_EVENT_USERCOUNT", "30");
        DEFAULT_PARAMETERS.put("SBRWR_LIVERYCODE_LENGTH", "8");
        DEFAULT_PARAMETERS.put("SBRWR_MAX_ITEM_INVENTORY", "100");
        DEFAULT_PARAMETERS.put("SBRWR_MAXCARSLOTS", "300");
        DEFAULT_PARAMETERS.put("SBRWR_MIN_CAR_DURABILITY", "0");
        DEFAULT_PARAMETERS.put("SBRWR_NOPU_REQUIREDPERCENT", "0");
        DEFAULT_PARAMETERS.put("SBRWR_PRESENCEEXPIRATIONTIME", "300");
        DEFAULT_PARAMETERS.put("SBRWR_RACE_AGAIN_MIN_TIME", "20000");
        DEFAULT_PARAMETERS.put("SBRWR_RACENOW_AUTO_CREATE_DELAY", "0");
        DEFAULT_PARAMETERS.put("SBRWR_RACENOW_MAX_WAIT_MINUTES", "5");
        DEFAULT_PARAMETERS.put("SBRWR_RACENOW_MONITOR_INTERVAL", "10000");
        DEFAULT_PARAMETERS.put("SBRWR_READY_THRESHOLD", "0");
        DEFAULT_PARAMETERS.put("SBRWR_REGIONINFO_SETCOUNTDOWNPROPOSAL", "3000");
        DEFAULT_PARAMETERS.put("SBRWR_REGIONINFO_SETDIRECTCONNECTIONTIMEOUT", "1000");
        DEFAULT_PARAMETERS.put("SBRWR_REGIONINFO_SETDROPOUTTIME", "15000");
        DEFAULT_PARAMETERS.put("SBRWR_REGIONINFO_SETEVENTLOADTIMEOUT", "30000");
        DEFAULT_PARAMETERS.put("SBRWR_REGIONINFO_SETHEARTBEATINTERVAL", "1000");
        DEFAULT_PARAMETERS.put("SBRWR_REGIONINFO_SETUDPRELAYBANDWIDTH", "9600");
        DEFAULT_PARAMETERS.put("SBRWR_REGIONINFO_SETUDPRELAYTIMEOUT", "60000");
        DEFAULT_PARAMETERS.put("SBRWR_TIME_THRESHOLD", "10000");
        DEFAULT_PARAMETERS.put("TREASURE_HUNT_COINS", "15");
        DEFAULT_PARAMETERS.put("UDP_FREEROAM_PORT", "0");
        DEFAULT_PARAMETERS.put("UDP_RACE_PORT", "0");
        DEFAULT_PARAMETERS.put("XMPP_PORT", "5222");

        // Str parameters
        DEFAULT_PARAMETERS.put("ADMIN_AUTH", "");
        DEFAULT_PARAMETERS.put("ANNOUNCEMENT_AUTH", "");
        DEFAULT_PARAMETERS.put("ANNOUNCEMENT_DOMAIN", "");
        DEFAULT_PARAMETERS.put("ARGON2_PARAMS", "500:16384:1");
        DEFAULT_PARAMETERS.put("BLACKLISTED_NICKNAMES", "");
        DEFAULT_PARAMETERS.put("CUSTOM_ELECTRON_LAUNCHER_URL", "N/A");
        DEFAULT_PARAMETERS.put("CUSTOM_HORIZON_LAUNCHER_URL", "N/A");
        DEFAULT_PARAMETERS.put("CUSTOM_SOAPBOX_LAUNCHER_URL", "N/A");
        DEFAULT_PARAMETERS.put("CUSTOM_WEBBASED_LAUNCHER_URL", "N/A");
        DEFAULT_PARAMETERS.put("DEFAULT_BAN_REASON", "No reason provided.");
        DEFAULT_PARAMETERS.put("DISCORD_WEBHOOK_BANREPORT_NAME", "Botte");
        DEFAULT_PARAMETERS.put("DISCORD_WEBHOOK_BANREPORT_PUBLIC_URL", "");
        DEFAULT_PARAMETERS.put("DISCORD_WEBHOOK_BANREPORT_URL", "");
        DEFAULT_PARAMETERS.put("DISCORD_WEBHOOK_DEFAULTNAME", "");
        DEFAULT_PARAMETERS.put("DISCORD_WEBHOOK_DEFAULTURL", "");
        DEFAULT_PARAMETERS.put("DISCORD_WEBHOOK_LEVEL_NAME", "Botte");
        DEFAULT_PARAMETERS.put("DISCORD_WEBHOOK_LEVEL_URL", "");
        DEFAULT_PARAMETERS.put("DISCORD_WEBHOOK_LOBBY_NAME", "[SBRW] Server");
        DEFAULT_PARAMETERS.put("DISCORD_WEBHOOK_LOBBY_URL", "");
        DEFAULT_PARAMETERS.put("DISCORD_WEBHOOK_REPORT_NAME", "Botte");
        DEFAULT_PARAMETERS.put("DISCORD_WEBHOOK_REPORT_URL", "");
        DEFAULT_PARAMETERS.put("EMAIL_FROM", "");
        DEFAULT_PARAMETERS.put("GEOIP2_DB_FILE_PATH", "");
        DEFAULT_PARAMETERS.put("MODDING_BASE_PATH", "");
        DEFAULT_PARAMETERS.put("MODDING_SERVER_ID", "");
        DEFAULT_PARAMETERS.put("OPENFIRE_ADDRESS", "");
        DEFAULT_PARAMETERS.put("OPENFIRE_TOKEN", "");
        DEFAULT_PARAMETERS.put("PORTAL_DOMAIN", "soapboxrace.world");
        DEFAULT_PARAMETERS.put("PORTAL_FAILURE_PAGE", "soapboxrace.world/fail");
        DEFAULT_PARAMETERS.put("REDIS_HOST", "localhost");
        DEFAULT_PARAMETERS.put("REDIS_PASSWORD", "");
        DEFAULT_PARAMETERS.put("SBRWR_BANNED_MP_POWERUPS", "");
        DEFAULT_PARAMETERS.put("SBRWR_DEFAULT_UA", "SBRWR-Core/NRZ-Branch");
        DEFAULT_PARAMETERS.put("SBRWR_DEFAULTREPORTER", "SBRW Reloaded");
        DEFAULT_PARAMETERS.put("SBRWR_NR_CREWFORMAT", "{persona}");
        DEFAULT_PARAMETERS.put("SBRWR_POST_VALID_22", "N/A");
        DEFAULT_PARAMETERS.put("SBRWR_TIMEZONE", "Europe/Paris");
        DEFAULT_PARAMETERS.put("SBRWR_XMPP_APPEND", "sbrw");
        DEFAULT_PARAMETERS.put("SENTRY_DSN", "");
        DEFAULT_PARAMETERS.put("SERVER_ADDRESS", "");
        DEFAULT_PARAMETERS.put("SERVER_INFO_ADMINS", "");
        DEFAULT_PARAMETERS.put("SERVER_INFO_ALLOWED_COUNTRIES", "");
        DEFAULT_PARAMETERS.put("SERVER_INFO_BANNER_URL", "");
        DEFAULT_PARAMETERS.put("SERVER_INFO_COUNTRY", "");
        DEFAULT_PARAMETERS.put("SERVER_INFO_DISCORD_URL", "");
        DEFAULT_PARAMETERS.put("SERVER_INFO_DISCORDAPPLICATIONID", "");
        DEFAULT_PARAMETERS.put("SERVER_INFO_FACEBOOK_URL", "");
        DEFAULT_PARAMETERS.put("SERVER_INFO_HOMEPAGE_URL", "");
        DEFAULT_PARAMETERS.put("SERVER_INFO_MESSAGE", "");
        DEFAULT_PARAMETERS.put("SERVER_INFO_NAME", "");
        DEFAULT_PARAMETERS.put("SERVER_INFO_OWNERS", "");
        DEFAULT_PARAMETERS.put("SERVER_INFO_RESETURL", "");
        DEFAULT_PARAMETERS.put("SERVER_INFO_SIGNUPURL", "");
        DEFAULT_PARAMETERS.put("SERVER_INFO_TWITTER_URL", "");
        DEFAULT_PARAMETERS.put("SERVER_INFO_WEBPANEL", "");
        DEFAULT_PARAMETERS.put("SERVER_PASSWORD_RESET_PATH", "/soapbox-race-core/password.jsp");
        DEFAULT_PARAMETERS.put("SIGNED_LAUNCHER_HASH", "");
        DEFAULT_PARAMETERS.put("SIGNED_LAUNCHER_HWID_WL", "");
        DEFAULT_PARAMETERS.put("STARTING_INVENTORY_ITEMS", "");
        DEFAULT_PARAMETERS.put("TICKET_TOKEN", "");
        DEFAULT_PARAMETERS.put("UDP_FREEROAM_IP", "");
        DEFAULT_PARAMETERS.put("UDP_RACE_IP", "");
        DEFAULT_PARAMETERS.put("WHITELISTED_LAUNCHERS_ONLY", "");
        DEFAULT_PARAMETERS.put("XMPP_IP", "127.0.0.1");
        DEFAULT_PARAMETERS.put("XMPP_PROVIDER", "");

        // Float parameters
        DEFAULT_PARAMETERS.put("CASH_REWARD_MULTIPLIER", "1.0");
        DEFAULT_PARAMETERS.put("happyHourMultipler", "1.0");
        DEFAULT_PARAMETERS.put("INVENTORY_ITEM_RESALE_MULTIPLIER", "1.0");
        DEFAULT_PARAMETERS.put("PLAYERCOUNT_REWARD_DIVIDER", "0.0");
        DEFAULT_PARAMETERS.put("REP_REWARD_MULTIPLIER", "1.0");
        DEFAULT_PARAMETERS.put("TH_CASH_MULTIPLIER", "1.0");
        DEFAULT_PARAMETERS.put("TH_REP_MULTIPLIER", "1.0");
    }

    public ParameterBO() {
        parameterMap = new ConcurrentHashMap<>();
    }
    
    @PostConstruct
    public void init() {
        loadParameters();
    }
    /**
     * Loads parameters from the database
     */
    public void loadParameters() {
        parameterMap.clear();
        for (ParameterEntity parameterEntity : parameterDao.findAll()) {
            if (parameterEntity.getValue() != null)
                parameterMap.put(parameterEntity.getName(), parameterEntity.getValue());
        }
        registerDefaults();
        updateLogLevel();
    }

    private void registerDefaults() {
        for (Map.Entry<String, String> entry : DEFAULT_PARAMETERS.entrySet()) {
            if (!parameterMap.containsKey(entry.getKey())) {
                setParameter(entry.getKey(), entry.getValue());
            }
        }
    }

    private void updateLogLevel() {
        boolean debugEnabled = Boolean.parseBoolean(parameterMap.getOrDefault("SBRWR_ENABLEDEBUG", "false"));
        java.util.logging.Logger appLogger = java.util.logging.Logger.getLogger("com.soapboxrace");
        if (debugEnabled) {
            appLogger.setLevel(Level.ALL);
        } else {
            appLogger.setLevel(Level.WARNING);
        }
    }

    private String getParameter(String name) {
        return parameterMap.get(name);
    }

    public int getCarLimit(UserEntity userEntity) {
        return userEntity.getMaxCarSlots();
    }

    public int getMaxCash(UserEntity userEntity) {
        if (userEntity.isPremium()) {
            return getIntParam("MAX_PLAYER_CASH_PREMIUM", 9_999_999);
        }
        return getIntParam("MAX_PLAYER_CASH_FREE", 9_999_999);
    }

    public int getMaxLevel(UserEntity userEntity) {
        if (userEntity.isPremium()) {
            return getIntParam("MAX_PLAYER_LEVEL_PREMIUM", 60);
        }
        return getIntParam("MAX_PLAYER_LEVEL_FREE", 60);
    }

    public Integer getIntParam(String parameter) {
        String parameterFromDB = getParameter(parameter);

        if (parameterFromDB == null) {
            throw new RuntimeException("Cannot find integer parameter: " + parameter);
        }

        return Integer.valueOf(parameterFromDB);
    }

    public Boolean getBoolParam(String parameter) {
        String parameterFromDB = getParameter(parameter);

        if (parameterFromDB == null || parameterFromDB.isEmpty()) {
            setParameter(parameter, Boolean.FALSE.toString());
        }

        return Boolean.valueOf(parameterFromDB);
    }

    public String getStrParam(String parameter) {
        String parameterFromDB = getParameter(parameter);

        if (parameterFromDB == null || parameterFromDB.isEmpty()) {
            return "";
        }

        return parameterFromDB;
    }

    public List<String> getStrListParam(String parameter) {
        return getStrListParam(parameter, Collections.emptyList());
    }

    public Float getFloatParam(String parameter) {
        String parameterFromDB = getParameter(parameter);

        if (parameterFromDB == null) {
            throw new RuntimeException("Cannot find float parameter: " + parameter);
        }

        return Float.valueOf(parameterFromDB);
    }


    public Integer getIntParam(String parameter, Integer defaultValue) {
        String parameterFromDB = getParameter(parameter);

        if (parameterFromDB == null || parameterFromDB.isEmpty()) {
            setParameter(parameter, defaultValue.toString());
            return defaultValue;
        }

        return Integer.valueOf(parameterFromDB);
    }

    public String getStrParam(String parameter, String defaultValue) {
        String parameterFromDB = getParameter(parameter);

        if (parameterFromDB == null || parameterFromDB.isEmpty()) {
            setParameter(parameter, defaultValue);
            return defaultValue;
        }

        return parameterFromDB;
    }

    public List<String> getStrListParam(String parameter, List<String> defaultValue) {
        String parameterFromDB = getParameter(parameter);

        if (parameterFromDB == null || parameterFromDB.isEmpty()) {
            setParameter(parameter, defaultValue.toString());
            return defaultValue;
        }

        return Arrays.asList(parameterFromDB.split(";"));
    }

    public Float getFloatParam(String parameter, Float defaultValue) {
        String parameterFromDB = getParameter(parameter);

        if(parameterFromDB == null || parameterFromDB.isEmpty()) {
            setParameter(parameter, defaultValue.toString());
            return defaultValue;
        }
        
        return Float.valueOf(parameterFromDB);
    }

    public void setParameter(String name, String value) {
        parameterMap.put(name, value);

        ParameterEntity entity = new ParameterEntity();
        entity.setName(name);
        entity.setValue(value);
        parameterDao.update(entity);
    }
}
