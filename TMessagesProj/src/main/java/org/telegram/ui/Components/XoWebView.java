package org.telegram.ui.Components;

import android.os.Build;
import android.webkit.WebView;

import org.telegram.messenger.FileLog;

/**
 * T70 hardening (multi-device guarantee): de-googled ROMs can ship with NO
 * WebView provider at all — every direct {@code new WebView(...)} then throws
 * WebViewFactory$MissingWebViewPackageException (an AndroidRuntimeException)
 * and kills the app. Upstream guards exactly one Samsung model by name and
 * leaves the rest unguarded.
 *
 * <p>This probe answers "can this device inflate a WebView?" ONCE, cheaply;
 * embed entry points (EmbedBottomSheet, PhotoViewer embed video) check it and
 * fall back to the external browser instead of crashing. Devices that do have
 * a provider (including microG/replaced providers) are unaffected.</p>
 */
public final class XoWebView {

    private static volatile Boolean available;

    private XoWebView() {
    }

    public static boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        boolean result;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                result = WebView.getCurrentWebViewPackage() != null;
            } else {
                // Pre-O devices always shipped the bundled provider.
                result = true;
            }
        } catch (Throwable t) {
            FileLog.e(t);
            result = false;
        }
        available = result;
        return result;
    }
}
