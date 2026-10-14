package com.vasthread.webviewtv.reconstructed;

import android.content.Context;
import android.util.DisplayMetrics;

/** The player requests landscape, but its first configuration can still be portrait. */
final class UiScale {
    private UiScale() { }

    static boolean isTelevision(Context context) {
        return (context.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_TYPE_MASK)
                == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;
    }
    static float text(Context context, float size, float scale) {
        return isTelevision(context) ? size * scale : Math.max(size * scale, size * context.getResources().getDisplayMetrics().scaledDensity * .64f);
    }

    static float landscape(Context context, int referenceWidth) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int width = Math.max(metrics.widthPixels, metrics.heightPixels);
        int height = Math.min(metrics.widthPixels, metrics.heightPixels);
        return Math.min(height / 720f, width / (float) referenceWidth);
    }
}
