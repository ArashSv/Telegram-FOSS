package org.telegram.tgnet.rest;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.TLRPC;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * T4: async bridge between LoginActivity and the blocking {@link RestGateway}.
 *
 * <p>One private single-thread executor performs the blocking I/O; every
 * callback is marshalled to the UI thread, matching the
 * {@code sendRequest(..., (response, error) -> runOnUIThread(...))} shape the
 * LoginActivity call sites already use.
 *
 * <p>Failures are mapped to {@link TLRPC.TL_error} with the exact text codes
 * the existing LoginActivity error chains already handle (PHONE_CODE_INVALID,
 * PHONE_CODE_EXPIRED, FLOOD_WAIT_*, PHONE_NUMBER_INVALID) so the intercepted
 * sites keep their original UX (shake, alerts, view transitions) unchanged.
 * Transport failures surface as a generic error alert — the login flow has no
 * retry loop; the user taps again.
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

    /** POST /auth/send-code.php off the UI thread. */
    public static void sendCode(int account, String phone, Callback<RestGateway.SendCodeResult> callback) {
        IO_QUEUE.execute(() -> {
            try {
                RestGateway.SendCodeResult result = RestGateway.getInstance(account).sendCode(phone);
                AndroidUtilities.runOnUIThread(() -> callback.onResult(result));
            } catch (Exception e) {
                FileLog.e("RestAuthController: sendCode failed", e);
                TLRPC.TL_error error = toTlError(e);
                AndroidUtilities.runOnUIThread(() -> callback.onError(error));
            }
        });
    }

    /** POST /auth/verify.php off the UI thread (single call does signIn AND signUp). */
    public static void verify(int account, String phone, String phoneCodeHash, String code,
                              String firstName, String lastName, Callback<RestGateway.VerifyResult> callback) {
        IO_QUEUE.execute(() -> {
            try {
                RestGateway.VerifyResult result = RestGateway.getInstance(account)
                        .verify(phone, phoneCodeHash, code, firstName, lastName);
                AndroidUtilities.runOnUIThread(() -> callback.onResult(result));
            } catch (Exception e) {
                FileLog.e("RestAuthController: verify failed", e);
                TLRPC.TL_error error = toTlError(e);
                AndroidUtilities.runOnUIThread(() -> callback.onError(error));
            }
        });
    }

    /**
     * Synthetic {@code TL_auth_sentCode} (SMS type) so the existing
     * {@code fillNextCodeParams} machinery routes to the standard code view
     * untouched: phone_code_hash + length drive it, timeout drives the timer.
     */
    public static TLRPC.TL_auth_sentCode toSentCode(RestGateway.SendCodeResult result) {
        TLRPC.TL_auth_sentCode sentCode = new TLRPC.TL_auth_sentCode();
        TLRPC.TL_auth_sentCodeTypeSms type = new TLRPC.TL_auth_sentCodeTypeSms();
        type.length = result.codeLength > 0 ? result.codeLength : 5;
        sentCode.type = type;
        sentCode.phone_code_hash = result.phoneCodeHash;
        sentCode.timeout = result.expiresIn > 0 ? result.expiresIn : 300;
        return sentCode;
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

    /** Maps failures to the TL_error strings the LoginActivity chains understand. */
    private static TLRPC.TL_error toTlError(Exception e) {
        TLRPC.TL_error error = new TLRPC.TL_error();
        if (e instanceof XoApiException) {
            XoApiException api = (XoApiException) e;
            error.code = api.httpStatus;
            String backend = api.errorCode;
            if ("INVALID_CODE".equals(backend)) {
                error.text = "PHONE_CODE_INVALID";
            } else if ("CODE_EXPIRED".equals(backend)) {
                error.text = "PHONE_CODE_EXPIRED";
            } else if ("TOO_MANY_ATTEMPTS".equals(backend)) {
                error.text = "FLOOD_WAIT_60";
            } else if ("VALIDATION_ERROR".equals(backend)) {
                error.text = "PHONE_NUMBER_INVALID";
            } else if (api.getMessage() != null && api.getMessage().length() > 0) {
                error.text = api.getMessage();
            } else {
                error.text = backend != null ? backend : "SERVER_ERROR";
            }
        } else {
            error.code = -1; // transport-level (XoTransportException or unexpected)
            error.text = e.getMessage() != null ? e.getMessage() : "NO_CONNECTION";
        }
        return error;
    }
}
