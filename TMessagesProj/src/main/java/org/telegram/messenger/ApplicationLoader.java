/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.view.ViewGroup;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.multidex.MultiDex;


import org.json.JSONObject;
import org.telegram.messenger.voip.VideoCapturerDevice;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Adapters.DrawerLayoutAdapter;
import org.telegram.ui.Components.ForegroundDetector;
import org.telegram.ui.Components.Premium.boosts.BoostRepository;
import org.telegram.ui.IUpdateLayout;
import org.telegram.ui.LauncherIconController;

import java.io.File;
import java.util.ArrayList;

public class ApplicationLoader extends Application {

    public static ApplicationLoader applicationLoaderInstance;

    @SuppressLint("StaticFieldLeak")
    public static volatile Context applicationContext;
    public static volatile NetworkInfo currentNetworkInfo;
    public static volatile Handler applicationHandler;

    private static ConnectivityManager connectivityManager;
    private static volatile boolean applicationInited = false;
    private static volatile  ConnectivityManager.NetworkCallback networkCallback;
    private static long lastNetworkCheckTypeTime;
    private static int lastKnownNetworkType = -1;

    public static long startTime;

    public static volatile boolean isScreenOn = false;
    public static volatile boolean mainInterfacePaused = true;
    public static volatile boolean mainInterfaceStopped = true;
    public static volatile boolean externalInterfacePaused = true;
    public static volatile boolean mainInterfacePausedStageQueue = true;
    public static boolean canDrawOverlays;
    public static volatile long mainInterfacePausedStageQueueTime;

    private static PushListenerController.IPushListenerServiceProvider pushProvider;
    private static ILocationServiceProvider locationServiceProvider;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
        // T66: install the black-box recorder BEFORE the ContentProvider
        // phase — providers run between attachBaseContext and onCreate and
        // were an unobserved crash window in T65.
        XoCrash.install();
    }

    public static PushListenerController.IPushListenerServiceProvider getPushProvider() {
        if (pushProvider == null) {
            pushProvider = applicationLoaderInstance.onCreatePushProvider();
        }
        return pushProvider;
    }

    protected PushListenerController.IPushListenerServiceProvider onCreatePushProvider() {
        return PushListenerController.GooglePushListenerServiceProvider.INSTANCE;
    }

    public static String getApplicationId() {
        return applicationLoaderInstance.onGetApplicationId();
    }

    protected String onGetApplicationId() {
        return null;
    }

    public static boolean isHuaweiStoreBuild() {
        return applicationLoaderInstance.isHuaweiBuild();
    }

    public static boolean isStandaloneBuild() {
        return true;
    }

    protected boolean isHuaweiBuild() {
        return false;
    }

    protected boolean isStandalone() {
        return true;
    }

    public static File getFilesDirFixed() {
        for (int a = 0; a < 10; a++) {
            try {
                File path = ApplicationLoader.applicationContext != null ? ApplicationLoader.applicationContext.getFilesDir() : null;
                if (path != null) {
                    return path;
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        try {
            ApplicationInfo info = applicationContext.getApplicationInfo();
            File path = new File(info.dataDir, "files");
            path.mkdirs();
            return path;
        } catch (Exception e) {
            FileLog.e(e);
        }
        // T70 hardening: the hardcoded fallback was org.telegram.messenger —
        // stale since the applicationId became com.hermes.chat.
        String pkg = "com.hermes.chat";
        try {
            if (applicationContext != null) {
                pkg = applicationContext.getPackageName();
            }
        } catch (Exception ignore) {
        }
        return new File("/data/data/" + pkg + "/files");
    }

    public static void postInitApplication() {
        if (applicationInited || applicationContext == null) {
            return;
        }
        applicationInited = true;
        NativeLoader.initNativeLibs(ApplicationLoader.applicationContext);

        try {
            LocaleController.getInstance(); //TODO improve
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            BroadcastReceiver networkStateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        currentNetworkInfo = connectivityManager.getActiveNetworkInfo();
                    } catch (Throwable ignore) {

                    }

                    boolean isSlow = isConnectionSlow();
                    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                        ConnectionsManager.getInstance(a).checkConnection();
                        FileLoader.getInstance(a).onNetworkChanged(isSlow);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
            ApplicationLoader.applicationContext.registerReceiver(networkStateReceiver, filter);
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            final BroadcastReceiver mReceiver = new ScreenReceiver();
            applicationContext.registerReceiver(mReceiver, filter);
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            PowerManager pm = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
            isScreenOn = pm.isScreenOn();
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("screen state = " + isScreenOn);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        SharedConfig.loadConfig();
        SharedPrefsHelper.init(applicationContext);
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) { //TODO improve account
            UserConfig.getInstance(a).loadConfig();
            MessagesController.getInstance(a);
            if (a == 0) {
                SharedConfig.pushStringStatus = "__FIREBASE_GENERATING_SINCE_" + ConnectionsManager.getInstance(a).getCurrentTime() + "__";
            } else {
                ConnectionsManager.getInstance(a);
            }
            TLRPC.User user = UserConfig.getInstance(a).getCurrentUser();
            if (user != null) {
                MessagesController.getInstance(a).putUser(user, true);
                SendMessagesHelper.getInstance(a).checkUnsentMessages();
            }
        }

        ApplicationLoader app = (ApplicationLoader) ApplicationLoader.applicationContext;
        //app.initPushServices();
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("app initied");
        }

        MediaController.getInstance();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) { //TODO improve account
            ContactsController.getInstance(a).checkAppAccount();
            DownloadController.getInstance(a);
        }
    }

    /**
     * T65: true when running in the default (main) process. The crash-report
     * offer must only fire there — the :crash process re-runs onCreate and
     * would otherwise loop. Application.getProcessName() needs API 28;
     * older devices read /proc/self/cmdline.
     */
    private boolean isMainProcess() {
        try {
            String name = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                name = ApplicationLoader.this.getProcessName();
            }
            if (name == null || name.length() == 0) {
                name = readProcCmdline();
            }
            if (name == null || name.length() == 0) {
                return true; // unknown -> treat as main (report offer is idempotent)
            }
            return name.equals(getPackageName());
        } catch (Throwable ignore) {
            return true;
        }
    }

    private static String readProcCmdline() {
        try (java.io.FileInputStream in = new java.io.FileInputStream("/proc/self/cmdline")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            String s = new String(out.toByteArray(), "UTF-8").trim();
            int zero = s.indexOf('\0');
            return zero >= 0 ? s.substring(0, zero) : s;
        } catch (Throwable ignore) {
            return null;
        }
    }

    public ApplicationLoader() {
        super();
    }

    @Override
    public void onCreate() {
        applicationLoaderInstance = this;
        try {
            applicationContext = getApplicationContext();
        } catch (Throwable ignore) {

        }

        super.onCreate();

        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("app start time = " + (startTime = SystemClock.elapsedRealtime()));
            try {
                FileLog.d("buildVersion = " + ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0).versionCode);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        if (applicationContext == null) {
            applicationContext = getApplicationContext();
        }

        // T66: offer a pending crash report from a previous run. Only the
        // MAIN process offers it. The report is NOT consumed here — the
        // report activity marks it offered after it actually renders, so a
        // silently blocked start (Android 15 background-activity-launch)
        // or a re-crash can never destroy the report (T65 flaw).
        if (isMainProcess()) {
            String pendingReport = null;
            try {
                pendingReport = XoCrash.peekUnofferedReport();
            } catch (Throwable ignore) {
            }
            if (pendingReport != null) {
                try {
                    Intent reportIntent = new Intent(this, XoCrashReportActivity.class);
                    reportIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    reportIntent.putExtra("report", pendingReport);
                    startActivity(reportIntent);
                } catch (Throwable ignore) {
                    // LaunchActivity.onCreate has a foreground fallback for
                    // this exact case; nothing is lost here anymore.
                }
            }
            // T66: diagnostic delivery channel — re-upload archived reports
            // that never reached the backend + launch ping (device, version,
            // pending-crash flag). Daemon, MIN_PRIORITY, fully guarded.
            try {
                Thread diag = new Thread(() -> {
                    XoCrash.resendUnsentReports();
                    XoCrash.sendLaunchPing();
                }, "XoCrashDiag");
                diag.setDaemon(true);
                diag.setPriority(Thread.MIN_PRIORITY);
                diag.start();
            } catch (Throwable ignore) {
            }
        }

        // T66: the native fail-fast below killed the :crash report process
        // before its activity could render (the "report screen never
        // appears" bug) — native init is a MAIN-process concern only. The
        // :crash process is fully programmatic UI with zero native deps.
        if (isMainProcess()) {
            NativeLoader.initNativeLibs(ApplicationLoader.applicationContext);
            try {
                ConnectionsManager.native_setJava(false);
            } catch (UnsatisfiedLinkError error) {
                throw new RuntimeException("can't load native libraries " +  Build.CPU_ABI + " lookup folder " + NativeLoader.getAbiFolder());
            }
        }
        new ForegroundDetector(this) {
            @Override
            public void onActivityStarted(Activity activity) {
                boolean wasInBackground = isBackground();
                super.onActivityStarted(activity);
                if (wasInBackground) {
                    ensureCurrentNetworkGet(true);
                }
            }
        };
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("load libs time = " + (SystemClock.elapsedRealtime() - startTime));
        }

        applicationHandler = new Handler(applicationContext.getMainLooper());

        // T67: the keep-alive foreground service (NotificationsService) is
        // eradicated. Its onCreate built a FLAG_MUTABLE PendingIntent with an
        // implicit intent — a hard IllegalArgumentException on Android 14/15/16
        // for targetSdk 34+ apps. The system killed the process at service
        // creation, i.e. the moment the splash exits ("Unable to create service
        // org.telegram.messenger.NotificationsService": POCO serenity A15,
        // Samsung a36xq A16, crash-1ccc947c/139f58be/95488ca2/dd357528).
        // Nothing starts a push service anymore; see NotificationsService.java.

        // T70 hardening: getComponentEnabledSetting/setComponentEnabledSetting
        // have thrown on OEM builds (both processes run this — Application.onCreate).
        try {
            LauncherIconController.tryFixLauncherIconIfNeeded();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        try {
            ProxyRotationController.init();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        // SET TFOSS USERAGENT FOR OSM SERVERS
        PackageInfo pInfo;
        String VERSIONNAME="";
        try {
            pInfo = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            VERSIONNAME = pInfo.versionName;
        } catch (Exception e) {
            FileLog.e(e);
        }
        org.osmdroid.config.Configuration.getInstance().setUserAgentValue("Hermes "+VERSIONNAME);
        org.osmdroid.config.Configuration.getInstance().setOsmdroidBasePath(new File(getCacheDir(),"osmdroid"));
    }

    // T67: ApplicationLoader.startPushService() was DELETED here — it was the
    // only thing launching the keep-alive NotificationsService whose onCreate
    // crashed the whole process on Android 14/15/16 (see NotificationsService).
    // All former call sites (onCreate, AppStartReceiver BOOT_COMPLETED,
    // MessagesController, NotificationsSettingsActivity) were removed with it.

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // T67: the :crash report process re-runs ApplicationLoader by design
        // (T66) but native libs are main-process-only, so it must NEVER touch
        // Telegram singletons: LocaleController.getInstance() pulls
        // MessagesController.getGlobalMainSettings() -> ConnectionsManager ->
        // native_isTestBackend -> UnsatisfiedLinkError inside the crash
        // reporter itself (field report crash-06d40a41, POCO serenity A15).
        if (!isMainProcess()) {
            return;
        }
        try {
            LocaleController.getInstance().onDeviceConfigurationChange(newConfig);
            AndroidUtilities.checkDisplaySize(applicationContext, newConfig);
            VideoCapturerDevice.checkScreenCapturerSize();
            AndroidUtilities.resetTabletFlag();
        } catch (Throwable t) {
            // A configuration change must never kill ANY process. Note:
            // UnsatisfiedLinkError and friends are Errors, not Exceptions —
            // the previous "catch (Exception e)" is exactly how the :crash
            // process died despite being wrapped.
            t.printStackTrace();
        }
    }
/*
    private void initPushServices() {
        AndroidUtilities.runOnUIThread(() -> {
            if (getPushProvider().hasServices()) {
                getPushProvider().onRequestPushToken();
            } else {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("No valid " + getPushProvider().getLogTitle() + " APK found.");
                }
                SharedConfig.pushStringStatus = "__NO_GOOGLE_PLAY_SERVICES__";
                PushListenerController.sendRegistrationToServer(getPushProvider().getPushType(), null);
            }
        }, 1000);
    }

    private boolean checkPlayServices() {
        try {
            int resultCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this);
            return resultCode == ConnectionResult.SUCCESS;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return true;
    }
*/

    private static long lastNetworkCheck = -1;
    private static void ensureCurrentNetworkGet() {
        final long now = System.currentTimeMillis();
        ensureCurrentNetworkGet(now - lastNetworkCheck > 5000);
        lastNetworkCheck = now;
    }

    private static void ensureCurrentNetworkGet(boolean force) {
        if (force || currentNetworkInfo == null) {
            try {
                if (connectivityManager == null) {
                    connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
                }
                currentNetworkInfo = connectivityManager.getActiveNetworkInfo();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    if (networkCallback == null) {
                        networkCallback = new ConnectivityManager.NetworkCallback() {
                            @Override
                            public void onAvailable(@NonNull Network network) {
                                lastKnownNetworkType = -1;
                            }

                            @Override
                            public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
                                lastKnownNetworkType = -1;
                            }
                        };
                        connectivityManager.registerDefaultNetworkCallback(networkCallback);
                    }
                }
            } catch (Throwable ignore) {

            }
        }
    }

    public static boolean isRoaming() {
        try {
            ensureCurrentNetworkGet(false);
            return currentNetworkInfo != null && currentNetworkInfo.isRoaming();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static boolean isConnectedOrConnectingToWiFi() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo != null && (currentNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI || currentNetworkInfo.getType() == ConnectivityManager.TYPE_ETHERNET)) {
                NetworkInfo.State state = currentNetworkInfo.getState();
                if (state == NetworkInfo.State.CONNECTED || state == NetworkInfo.State.CONNECTING || state == NetworkInfo.State.SUSPENDED) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static boolean isConnectedToWiFi() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo != null && (currentNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI || currentNetworkInfo.getType() == ConnectivityManager.TYPE_ETHERNET) && currentNetworkInfo.getState() == NetworkInfo.State.CONNECTED) {
                return true;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static boolean isConnectionSlow() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo != null && currentNetworkInfo.getType() == ConnectivityManager.TYPE_MOBILE) {
                switch (currentNetworkInfo.getSubtype()) {
                    case TelephonyManager.NETWORK_TYPE_1xRTT:
                    case TelephonyManager.NETWORK_TYPE_CDMA:
                    case TelephonyManager.NETWORK_TYPE_EDGE:
                    case TelephonyManager.NETWORK_TYPE_GPRS:
                    case TelephonyManager.NETWORK_TYPE_IDEN:
                        return true;
                }
            }
        } catch (Throwable ignore) {

        }
        return false;
    }

    public static int getAutodownloadNetworkType() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo == null) {
                return StatsController.TYPE_MOBILE;
            }
            if (currentNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI || currentNetworkInfo.getType() == ConnectivityManager.TYPE_ETHERNET) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && (lastKnownNetworkType == StatsController.TYPE_MOBILE || lastKnownNetworkType == StatsController.TYPE_WIFI) && System.currentTimeMillis() - lastNetworkCheckTypeTime < 5000) {
                    return lastKnownNetworkType;
                }
                if (connectivityManager.isActiveNetworkMetered()) {
                    lastKnownNetworkType = StatsController.TYPE_MOBILE;
                } else {
                    lastKnownNetworkType = StatsController.TYPE_WIFI;
                }
                lastNetworkCheckTypeTime = System.currentTimeMillis();
                return lastKnownNetworkType;
            }
            if (currentNetworkInfo.isRoaming()) {
                return StatsController.TYPE_ROAMING;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return StatsController.TYPE_MOBILE;
    }

    public static int getCurrentNetworkType() {
        if (isConnectedOrConnectingToWiFi()) {
            return StatsController.TYPE_WIFI;
        } else if (isRoaming()) {
            return StatsController.TYPE_ROAMING;
        } else {
            return StatsController.TYPE_MOBILE;
        }
    }

    public static boolean isNetworkOnlineFast() {
        try {
            ensureCurrentNetworkGet(false);
            if (currentNetworkInfo == null) {
                return true;
            }
            if (currentNetworkInfo.isConnectedOrConnecting() || currentNetworkInfo.isAvailable()) {
                return true;
            }

            NetworkInfo netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
            if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                return true;
            } else {
                netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            return true;
        }
        return false;
    }

    public static boolean isNetworkOnlineRealtime() {
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo netInfo = connectivityManager.getActiveNetworkInfo();
            if (netInfo != null && (netInfo.isConnectedOrConnecting() || netInfo.isAvailable())) {
                return true;
            }

            netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);

            if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                return true;
            } else {
                netInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
                if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            return true;
        }
        return false;
    }

    public static boolean isNetworkOnline() {
        boolean result = isNetworkOnlineRealtime();
        if (BuildVars.DEBUG_PRIVATE_VERSION) {
            boolean result2 = isNetworkOnlineFast();
            if (result != result2) {
                FileLog.d("network online mismatch");
            }
        }
        return result;
    }

    public static void startAppCenter(Activity context) {
        applicationLoaderInstance.startAppCenterInternal(context);
    }

    public static void checkForUpdates() {
        applicationLoaderInstance.checkForUpdatesInternal();
    }

    public static void appCenterLog(Throwable e) {
        applicationLoaderInstance.appCenterLogInternal(e);
    }

    protected void appCenterLogInternal(Throwable e) {

    }

    protected void checkForUpdatesInternal() {

    }

    protected void startAppCenterInternal(Activity context) {

    }

    public static void logDualCamera(boolean success, boolean vendor) {
        applicationLoaderInstance.logDualCameraInternal(success, vendor);
    }

    protected void logDualCameraInternal(boolean success, boolean vendor) {

    }

    public boolean checkApkInstallPermissions(final Context context) {
        return false;
    }

    public boolean openApkInstall(Activity activity, TLRPC.Document document) {
        return false;
    }

    public boolean showUpdateAppPopup(Context context, TLRPC.TL_help_appUpdate update, int account) {
        return false;
    }

    public IUpdateLayout takeUpdateLayout(Activity activity, ViewGroup sideMenu, ViewGroup sideMenuContainer) {
        return null;
    }

    public TLRPC.Update parseTLUpdate(int constructor) {
        return null;
    }

    public void processUpdate(int currentAccount, TLRPC.Update update) {

    }

    public boolean onSuggestionFill(String suggestion, CharSequence[] output, boolean[] closeable) {
        return false;
    }

    public boolean onSuggestionClick(String suggestion) {
        return false;
    }

    public boolean extendDrawer(ArrayList<DrawerLayoutAdapter.Item> items) {
        return false;
    }

    public boolean checkRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        return false;
    }

    public boolean consumePush(int account, JSONObject json) {
        return false;
    }

    public void onResume() {

    }

    public boolean onPause() {
        return false;
    }

    public BaseFragment openSettings(int n) {
        return null;
    }

}
