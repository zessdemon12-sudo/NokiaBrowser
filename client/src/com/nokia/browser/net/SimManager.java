package com.nokia.browser.net;

import com.nokia.browser.storage.StorageManager;

/**
 * Manages SIM card hardware detection, cellular network bearers,
 * carrier APN profiles, and mobile data usage accounting for Nokia S40/S60 devices.
 */
public class SimManager {

    // SIM Slots
    public static final int SIM_1 = 0;
    public static final int SIM_2 = 1;

    // Network Bearer Types
    public static final int BEARER_AUTO  = 0;
    public static final int BEARER_GPRS  = 1;
    public static final int BEARER_EDGE  = 2;
    public static final int BEARER_3G    = 3;
    public static final int BEARER_HSDPA = 4;
    public static final int BEARER_WIFI  = 5;

    // Carrier APN Presets
    public static final int APN_AUTO          = 0;
    public static final int APN_ROBI_WAP      = 1;
    public static final int APN_ROBI_INTERNET = 2;
    public static final int APN_VODAFONE      = 3;
    public static final int APN_TMOBILE       = 4;
    public static final int APN_ATT           = 5;
    public static final int APN_AIRTEL        = 6;
    public static final int APN_JIO           = 7;
    public static final int APN_ORANGE        = 8;
    public static final int APN_CUSTOM        = 9;

    private StorageManager storage;

    // Hardware / System properties
    private String detectedPlatform = "Nokia S40";
    private String detectedOperator = "Nokia Mobile";
    private String detectedCountryCode = "310"; // MCC
    private String detectedNetworkCode = "260"; // MNC
    private String detectedImei = "358921001234567";
    private String detectedImsi = "310260123456789";
    private boolean isDualSimDevice = false;
    private boolean isRoaming = false;

    // Dynamic runtime state
    private long sessionBytes = 0;
    private long lastDataTransferTime = 0;
    private int signalBars = 4; // 1 to 4 bars

    public SimManager(StorageManager storage) {
        this.storage = storage;
        detectHardware();
    }

    /**
     * Safely queries Nokia S40 / S60 system properties.
     */
    private void detectHardware() {
        try {
            String p = System.getProperty("microedition.platform");
            if (p != null && p.length() > 0) detectedPlatform = p;
        } catch (Throwable t) {}

        try {
            String op = System.getProperty("com.nokia.mid.networkid");
            if (op == null) op = System.getProperty("phone.sim.operator");
            if (op == null) op = System.getProperty("com.sonyericsson.net.operator");
            if (op != null && op.length() > 0) detectedOperator = op;
        } catch (Throwable t) {}

        try {
            String mcc = System.getProperty("com.nokia.mid.countrycode");
            if (mcc != null && mcc.length() > 0) detectedCountryCode = mcc;
        } catch (Throwable t) {}

        try {
            String mnc = System.getProperty("com.nokia.mid.networkcode");
            if (mnc != null && mnc.length() > 0) detectedNetworkCode = mnc;
        } catch (Throwable t) {}

        try {
            String imei = System.getProperty("com.nokia.mid.imei");
            if (imei != null && imei.length() > 0) detectedImei = imei;
        } catch (Throwable t) {}

        try {
            String imsi = System.getProperty("com.nokia.mid.imsi");
            if (imsi != null && imsi.length() > 0) detectedImsi = imsi;
        } catch (Throwable t) {}

        try {
            String dualSim = System.getProperty("com.nokia.mid.selectedsim");
            if (dualSim != null && dualSim.length() > 0) {
                isDualSimDevice = true;
            }
        } catch (Throwable t) {}

        try {
            String avail = System.getProperty("com.nokia.mid.networkavailability");
            if (avail != null && avail.toLowerCase().indexOf("roaming") >= 0) {
                isRoaming = true;
            }
        } catch (Throwable t) {}

        // Carrier operator detection (Bangladesh MCC 470)
        if ("470".equals(detectedCountryCode) && detectedNetworkCode != null && detectedNetworkCode.length() > 0) {
            char c = detectedNetworkCode.charAt(detectedNetworkCode.length() - 1);
            detectedOperator = (c == '1') ? "Grameenphone" : (c == '2') ? "Robi" : (c == '3') ? "Banglalink" : (c == '4') ? "Teletalk" : (c == '7') ? "Airtel" : detectedOperator;
        }
    }

    public int getActiveSim() {
        return storage.getSimSlot();
    }

    public void setActiveSim(int slot) {
        storage.setSimSlot(slot);
    }

    public int getBearer() {
        return storage.getNetworkBearer();
    }

    public void setBearer(int b) {
        storage.setNetworkBearer(b);
    }

    public int getApnPreset() {
        return storage.getApnPreset();
    }

    public void setApnPreset(int p) {
        storage.setApnPreset(p);
    }

    public String getApnName() {
        int preset = storage.getApnPreset();
        switch (preset) {
            case APN_ROBI_WAP:      return "WAP";
            case APN_ROBI_INTERNET: return "INTERNET";
            case APN_VODAFONE:      return "live.vodafone.com";
            case APN_TMOBILE:       return "fast.t-mobile.com";
            case APN_ATT:           return "phone";
            case APN_AIRTEL:        return "airtelgprs.com";
            case APN_JIO:           return "jionet";
            case APN_ORANGE:        return "orange";
            case APN_CUSTOM:
                String c = storage.getCustomApn();
                return (c != null && c.length() > 0) ? c : "internet";
            default:
                return "internet";
        }
    }

    public String getApnProxy() {
        int preset = storage.getApnPreset();
        switch (preset) {
            case APN_ROBI_WAP: return "10.16.18.77:9028";
            case APN_VODAFONE: return "10.10.1.100:8080";
            case APN_ORANGE:   return "192.168.10.100:8080";
            case APN_CUSTOM:   return storage.getCustomProxy();
            default:           return "";
        }
    }

    public String getBearerName() {
        int b = storage.getNetworkBearer();
        switch (b) {
            case BEARER_GPRS:  return "GPRS";
            case BEARER_EDGE:  return "EDGE";
            case BEARER_3G:    return "3G";
            case BEARER_HSDPA: return "HSDPA";
            case BEARER_WIFI:  return "WiFi";
            default:           return "EDGE";
        }
    }

    public String getBearerBadge() {
        int b = storage.getNetworkBearer();
        switch (b) {
            case BEARER_GPRS:  return "G";
            case BEARER_EDGE:  return "E";
            case BEARER_3G:    return "3G";
            case BEARER_HSDPA: return "H";
            case BEARER_WIFI:  return "W";
            case BEARER_AUTO:
            default:
                return "E";
        }
    }

    public String getSimBadge() {
        return (storage.getSimSlot() == SIM_2) ? "S2" : "S1";
    }

    public int getSignalBars() {
        return signalBars;
    }

    public void setSignalBars(int bars) {
        if (bars < 0) bars = 0;
        if (bars > 4) bars = 4;
        this.signalBars = bars;
    }

    public boolean isDataActive() {
        return (System.currentTimeMillis() - lastDataTransferTime) < 1500;
    }

    public synchronized void recordBytes(int count) {
        if (count <= 0) return;
        sessionBytes += count;
        lastDataTransferTime = System.currentTimeMillis();
        storage.addMobileBytes(count);
    }

    public long getSessionBytes() {
        return sessionBytes;
    }

    public long getTotalBytes() {
        return storage.getTotalMobileBytes();
    }

    public void resetTotalBytes() {
        storage.resetMobileBytes();
        sessionBytes = 0;
    }

    public boolean isDataSaver() {
        return storage.isDataSaver();
    }

    public void setDataSaver(boolean ds) {
        storage.setDataSaver(ds);
    }

    public String getDetectedPlatform() { return detectedPlatform; }
    public String getDetectedOperator() { return detectedOperator; }
    public String getDetectedCountryCode() { return detectedCountryCode; }
    public String getDetectedNetworkCode() { return detectedNetworkCode; }
    public boolean isRoaming() { return isRoaming; }
    public boolean isDualSim() { return isDualSimDevice; }

    private static String mask(String s, int p) {
        if (s == null || s.length() <= p) return "******";
        return s.substring(0, p) + "******" + s.substring(s.length() - 2);
    }

    public String getMaskedImei() { return mask(detectedImei, 6); }
    public String getMaskedImsi() { return mask(detectedImsi, 5); }

    public static String formatBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1048576) return (b / 1024) + "." + ((b % 1024) * 10 / 1024) + " KB";
        return (b / 1048576) + "." + ((b % 1048576) * 10 / 1048576) + " MB";
    }
}
