package com.tuman.free.core;

import android.os.Handler;
import android.os.Looper;

/** Tiny helper so background workers can hand results back to the UI thread. */
public final class Ui {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Ui() {
    }

    public static void post(Runnable r) {
        if (r == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else MAIN.post(r);
    }
}
