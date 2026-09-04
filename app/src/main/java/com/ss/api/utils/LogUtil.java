package com.ss.api.utils;

import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

public class LogUtil {
    private static final String TAG = "FireflyApi";
    public static boolean ENABLED = true;

    public LogUtil() {
    }

    public static void show(Context context, String msg) {
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
    }

    public static void show(Handler handler, final Context context, final String msg) {
        handler.post(new Runnable() {
            public void run() {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    public static void d(String msg) {
        if (ENABLED) {
            Log.d("FireflyApi[DEBUG]", msg);
        }

    }

    public static void d(Object obj, String msg) {
        if (ENABLED) {
            Log.d(obj.getClass().getSimpleName(), msg);
        }

    }

    public static void i(Object obj, String msg) {
        if (ENABLED) {
            Log.i(obj.getClass().getSimpleName(), msg);
        }

    }

    public static void v(Object obj, String msg) {
        if (ENABLED) {
            Log.v(obj.getClass().getSimpleName(), msg);
        }

    }

    public static void e(Object obj, String msg) {
        Log.e(obj.getClass().getSimpleName(), msg);
    }

    public static void e(Object obj, String msg, Exception ex) {
        Log.e(obj.getClass().getSimpleName(), msg, ex);
    }

    public static void e(String msg, Exception ex) {
        Log.e("FireflyApi[ERROR]", msg, ex);
    }

    public static void w(Object obj, String msg) {
        if (ENABLED) {
            Log.w(obj.getClass().getSimpleName(), msg);
        }

    }
}