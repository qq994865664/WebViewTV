package com.vasthread.webviewtv.reconstructed;

import android.content.Context;
import android.util.DisplayMetrics;

/** The player requests landscape, but its first configuration can still be portrait. */
final class UiScale {
    private UiScale() { }

    static float landscape(Context context, int referenceWidth) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int width = Math.max(metrics.widthPixels, metrics.heightPixels);
        int height = Math.min(metrics.widthPixels, metrics.heightPixels);
        return Math.min(height / 720f, width / (float) referenceWidth);
    }
}
