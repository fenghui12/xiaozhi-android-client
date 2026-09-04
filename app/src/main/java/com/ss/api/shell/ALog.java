package com.ss.api.shell;

import android.util.Log;

public class ALog {
    public static final String TAG = "AndroidTools";
    public static final int LOG_VERBOSE = 5;
    public static final int LOG_DEBUG = 4;
    public static final int LOG_INFO = 3;
    public static final int LOG_WARNING = 2;
    public static final int LOG_ERROR = 1;
    public static final int LOG_WTF = 0;
    public static final int LOG_QUIET = -1;
    private static int CURRENT_LOG_LEVEL = -1;

    public ALog() {
        CURRENT_LOG_LEVEL = 1;
    }

    public static void setLogLevel(int logLevel) {
        CURRENT_LOG_LEVEL = logLevel;
    }

    public static final void d(String message) {
        if (CURRENT_LOG_LEVEL >= 4) {
            Log.d("AndroidTools", message);
        }

    }

    public static final void d(String message, Throwable exception) {
        if (CURRENT_LOG_LEVEL >= 4) {
            Log.d("AndroidTools", message, exception);
        }

    }

    public static final void v(String message) {
        if (CURRENT_LOG_LEVEL >= 5) {
            Log.v("AndroidTools", message);
        }

    }

    public static final void v(String message, Throwable exception) {
        if (CURRENT_LOG_LEVEL >= 5) {
            Log.v("AndroidTools", message, exception);
        }

    }

    public static final void e(String message) {
        if (CURRENT_LOG_LEVEL >= 1) {
            Log.e("AndroidTools", message);
        }

    }

    public static final void e(String message, Throwable exception) {
        if (CURRENT_LOG_LEVEL >= 1) {
            Log.e("AndroidTools", message, exception);
        }

    }

    public static final void i(String message) {
        if (CURRENT_LOG_LEVEL >= 3) {
            Log.i("AndroidTools", message);
        }

    }

    public static final void i(String message, Throwable exception) {
        if (CURRENT_LOG_LEVEL >= 3) {
            Log.i("AndroidTools", message, exception);
        }

    }

    public static final void wtf(String message) {
        if (CURRENT_LOG_LEVEL >= 0) {
            Log.wtf("AndroidTools", message);
        }

    }

    public static final void wtf(String message, Throwable exception) {
        if (CURRENT_LOG_LEVEL >= 0) {
            Log.wtf("AndroidTools", message, exception);
        }

    }

    public static final void w(String message) {
        if (CURRENT_LOG_LEVEL >= 2) {
            Log.w("AndroidTools", message);
        }

    }

    public static final void w(String message, Throwable exception) {
        if (CURRENT_LOG_LEVEL >= 2) {
            Log.w("AndroidTools", message, exception);
        }

    }
}

