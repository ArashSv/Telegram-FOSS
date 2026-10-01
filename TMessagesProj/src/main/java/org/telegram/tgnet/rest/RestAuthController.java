package org.telegram.tgnet.rest;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.TLRPC;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * T4 → T50: async bridge between the login/privacy UI and the blocking
 * {@link RestGateway}.
 *
 * <p>One private single-thread executor performs the blocking I/O; every
 * callback is marshalled to the UI thread, matching the
 * {@code sendRequest(..., (response, error) -> runOnUIThread(...))} shape the
 * LoginActivity call sites already use.
 *
 * <p>T50 (password-first auth): the OTP pair (sendCode/verify) is GONE — the
 * bridge now exposes checkPhone / register / loginWithPassword /
 * updateProfile (setDisplayName). Failures are mapped to {@link TLRPC.TL_error}
 * whose text carries the backend code (PASSWORD_INVALID, ACCOUNT_EXISTS,
 * VALIDATION_ERROR) or FLOOD_WAIT_60 for throttle lockouts; transport failures
 * surface as code -1. The login flow has no retry loop — the user taps again.
 */
public final class RestAuthController {

    public interface Callback<T> {
        void onResult(T result);
        void onError(TLRPC.TL_error error);
    }

    private static final ExecutorService IO_QUEUE = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "RestAuthQueue");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private RestAuthController() {
    }

    /** POST /auth/check-phone.php off the UI thread (T50; v2.9.3 auth context). */
    public static void checkPhone(int account, String phone, String authBearer, Callback<RestGateway.CheckPhoneResult> callback) {
        IO_QUEUE.execute(() -> {
            try {
                RestGateway.CheckPhoneResult result = RestGateway.getInstance(account).checkPhone(phone, authBearer);
                AndroidUtilities.runOnUIThread(() -> callback.onResult(result));
            } catch (Exception e) {
                FileLog.e("RestAuthController: checkPhone failed", e);
                TLRPC.TL_error error = toTlError(e);
                AndroidUtilities.runOnUIThread(() -> callback.onError(error));
            }
        });
    }

    /** POST /auth/register.php off the UI thread — new number + password setup (T50; v2.9.3 auth context). */
    public static void register(int account, String phone, String password, String hint, String authBearer,
                                Callback<RestGateway.VerifyResult> callback) {
        IO_QUEUE.execute(() -> {
            try {
                RestGateway.VerifyResult result = RestGateway.getInstance(account)
                        .register(phone, password, hint, authBearer);
                AndroidUtilities.runOnUIThread(() -> callback.onResult(result));
            } catch (Exception e) {
                FileLog.e("RestAuthController: register failed", e);
                TLRPC.TL_error error = toTlError(e);
                AndroidUtilities.runOnUIThread(() -> callback.onError(error));
            }
        });
    }

    /** POST /auth/login.php off the UI thread — existing number + password (T50; v2.9.3 auth context). */
    public static void loginWithPassword(int account, String phone, String password, String authBearer,
                                         Callback<RestGateway.VerifyResult> callback) {
        IO_QUEUE.execute(() -> {
            try {
                RestGateway.VerifyResult result = RestGateway.getInstance(account)
                        .loginWithPassword(phone, password, authBearer);
                AndroidUtilities.runOnUIThread(() -> callback.onResult(result));
            } catch (Exception e) {
                FileLog.e("RestAuthController: loginWithPassword failed", e);
                TLRPC.TL_error error = toTlError(e);
                AndroidUtilities.runOnUIThread(() -> callback.onError(error));
            }
        });
    }

    /** POST /users/edit.php off the UI thread — the signup name page (T50). */
    public static void updateProfile(int account, String displayName, Callback<RestGateway.VerifyResult> callback) {
        IO_QUEUE.execute(() -> {
            try {
                RestGateway.VerifyResult result = RestGateway.getInstance(account).setDisplayName(displayName);
                AndroidUtilities.runOnUIThread(() -> callback.onResult(result));
            } catch (Exception e) {
                FileLog.e("RestAuthController: updateProfile failed", e);
                TLRPC.TL_error error = toTlError(e);
                AndroidUtilities.runOnUIThread(() -> callback.onError(error));
            }
        });
    }

    /** POST /auth/verify-password.php off the UI thread (privacy step, T50). */
    public static void verifyPassword(int account, String password, Callback<Boolean> callback) {
        IO_QUEUE.execute(() -> {
            try {
                boolean ok = RestGateway.getInstance(account).verifyPassword(password);
                AndroidUtilities.runOnUIThread(() -> callback.onResult(ok));
            } catch (Exception e) {
                FileLog.e("RestAuthController: verifyPassword failed", e);
                TLRPC.TL_error error = toTlError(e);
                AndroidUtilities.runOnUIThread(() -> callback.onError(error));
            }
        });
    }

    /** POST /auth/change-password.php off the UI thread (privacy wizard, T50). */
    public static void changePassword(int account, String currentPassword, String newPassword, String hint,
                                      Callback<RestGateway.VerifyResult> callback) {
        IO_QUEUE.execute(() -> {
            try {
                RestGateway.VerifyResult result = RestGateway.getInstance(account)
                        .changePassword(currentPassword, newPassword, hint);
                AndroidUtilities.runOnUIThread(() -> callback.onResult(result));
            } catch (Exception e) {
                FileLog.e("RestAuthController: changePassword failed", e);
                TLRPC.TL_error error = toTlError(e);
                AndroidUtilities.runOnUIThread(() -> callback.onError(error));
            }
        });
    }

    /**
     * Synthetic {@code TL_auth_authorization} carrying the mapped self user so
     * the existing {@code onAuthSuccess} completion path (UserConfig setCurrentUser,
     * storage upsert, UI finish) runs exactly as it does for MTProto logins.
     */
    public static TLRPC.TL_auth_authorization toAuthorization(RestGateway.VerifyResult result) {
        TLRPC.TL_auth_authorization authorization = new TLRPC.TL_auth_authorization();
        authorization.user = result.user;
        return authorization;
    }

    /**
     * Maps failures to TL_error. The backend's own code becomes the text
     * (PASSWORD_INVALID / ACCOUNT_EXISTS / VALIDATION_ERROR) so the password-era
     * views can branch on it; throttling is surfaced as the legacy
     * FLOOD_WAIT_60 shape the alert chains already understand.
     */
    private static TLRPC.TL_error toTlError(Exception e) {
        TLRPC.TL_error error = new TLRPC.TL_error();
        if (e instanceof XoApiException) {
            XoApiException api = (XoApiException) e;
            error.code = api.httpStatus;
            String backend = api.errorCode;
            if ("TOO_MANY_ATTEMPTS".equals(backend)) {
                error.text = "FLOOD_WAIT_60";
            } else if (backend != null && backend.length() > 0) {
                error.text = backend;
            } else {
                error.text = api.getMessage() != null ? api.getMessage() : "SERVER_ERROR";
            }
        } else {
            error.code = -1; // transport-level (XoTransportException or unexpected)
            error.text = e.getMessage() != null ? e.getMessage() : "NO_CONNECTION";
        }
        return error;
    }
}
