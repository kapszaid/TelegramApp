package com.nexgram.messenger;

import android.os.Build;

/**
 * NexGram Build and API Configuration
 * Manages Telegram API ID and Hash dynamically from BuildConfig/properties.
 */
public class BuildVars {
    public static final int APP_ID = BuildConfig.TELEGRAM_APP_ID;
    public static final String APP_HASH = BuildConfig.TELEGRAM_APP_HASH;
    public static final String APP_NAME = BuildConfig.APP_NAME;

    public static final String BUILD_VERSION_STRING = "1.0.0";
    public static final int BUILD_VERSION = 1;

    public static String getDeviceModel() {
        return Build.MANUFACTURER + " " + Build.MODEL;
    }

    public static String getSystemVersion() {
        return "Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")";
    }
}
