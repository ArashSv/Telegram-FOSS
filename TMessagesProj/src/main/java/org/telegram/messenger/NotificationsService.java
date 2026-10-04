/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * T67: INTENTIONALLY INERT — do not resurrect this service.
 *
 * WHY: this keep-alive foreground service was the root cause of the
 * Android 14/15/16 launch crash ("crash the moment the splash exits").
 * Its onCreate built:
 *
 *   new Intent("android.intent.action.VIEW")            // IMPLICIT intent
 *   PendingIntent.getActivity(..., FLAG_MUTABLE)        // MUTABLE flags
 *
 * A FLAG_MUTABLE PendingIntent with an implicit intent is a hard
 * IllegalArgumentException for apps targeting SDK 34+ ("Targeting U+
 * disallows creating or retrieving a PendingIntent with FLAG_MUTABLE, an
 * implicit Intent ..."). The service is created right after the splash
 * (startPushService), so the whole process died there: "Unable to create
 * service org.telegram.messenger.NotificationsService". START_STICKY made
 * the OS relaunch the dead service, producing a crash loop. Field reports:
 * crash-1ccc947c / 139f58be / 95488ca2 (POCO serenity, A15) and
 * crash-dd357528 / c00606ff (Samsung a36xq, A16). Android 13 was immune
 * only because that OS does not enforce the rule below SDK 34.
 *
 * NOTE (T67): the T70 "startForeground BEFORE heavy init" reordering and the
 * OEM channel guard in this file are SUPERSEDED by this eradication — an
 * inert service never runs, so neither the FGS 5s window nor channel
 * creation can ever crash again. The PendingIntent violation itself was the
 * actual field crash and no reordering could have fixed it.
 *
 * Per product decision Hermes does not need a keep-alive FGS at all. Every
 * start path was removed (ApplicationLoader.onCreate, AppStartReceiver
 * BOOT_COMPLETED, MessagesController, NotificationsSettingsActivity) and
 * ApplicationLoader.startPushService() was deleted. The class and its
 * manifest entry remain ONLY as an empty shell: a stale sticky-restart
 * queued by the OS from a previous install, or any remnant reference, now
 * resolves to a silent no-op instead of a crash.
 */
public class NotificationsService extends Service {

    @Override
    public void onCreate() {
        // No-op by design (T67). Must never create PendingIntents,
        // notifications, foreground state, alarms or broadcasts.
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_NOT_STICKY: if anything still restarts this shell, let it
        // exit immediately and never be restarted again.
        stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
