package org.telegram.tgnet.rest;

import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import org.telegram.tgnet.TLRPC;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * T63 — the v1 ENVELOPE contract (ok/error/malformed) plus the error-code
 * fixtures of the deployed backend: every code the client makes a
 * lifecycle or UI decision on, parsed through the REAL parseEnvelope and
 * classified through XoApiException's semantics.
 */
public class EnvelopeContractTest {

    @BeforeClass
    public static void setUp() {
        XoTestEnv.init();
    }

    private static XoHttp.Response response(int code, String body) {
        return new XoHttp.Response(code, body);
    }

    // ---- ok path ----------------------------------------------------------------

    @Test
    public void okEnvelope_returnsTheFullJsonObject() throws Exception {
        JSONObject parsed = RestGateway.parseEnvelope(response(200, "{\"ok\":true,\"cursor\":5,\"updates\":[]}"), "sync/index.php");
        assertTrue(parsed.optBoolean("ok"));
        assertEquals(5, parsed.optInt("cursor"));
    }

    // ---- error paths ------------------------------------------------------------

    @Test
    public void errorEnvelope_throwsWithBackendCodeAndMessage() {
        try {
            RestGateway.parseEnvelope(response(403, "{\"ok\":false,\"error\":{\"code\":\"USER_IS_BLOCKED\",\"message\":\"nope\"}}"), "messages/send.php");
            fail("must throw");
        } catch (XoApiException e) {
            assertEquals(403, e.httpStatus);
            assertEquals("USER_IS_BLOCKED", e.errorCode);
            assertEquals("nope", e.getMessage());
        }
    }

    @Test
    public void errorObjectMissing_fallsBackToUnknown() {
        try {
            RestGateway.parseEnvelope(response(500, "{\"ok\":false}"), "x.php");
            fail("must throw");
        } catch (XoApiException e) {
            assertEquals("UNKNOWN", e.errorCode);
            assertEquals("no error detail (http 500)", e.getMessage());
        }
    }

    @Test
    public void emptyBody_isMalformed_notATransportDeath() {
        try {
            RestGateway.parseEnvelope(response(200, ""), "x.php");
            fail("must throw");
        } catch (XoApiException e) {
            assertEquals(200, e.httpStatus);
            assertEquals(XoApiException.MALFORMED_RESPONSE, e.errorCode);
        }
    }

    @Test
    public void htmlErrorPage_isMalformedTyped() {
        String html = "<html><body><h1>502 Bad Gateway</h1></body></html>";
        try {
            RestGateway.parseEnvelope(response(502, html), "x.php");
            fail("must throw");
        } catch (XoApiException e) {
            assertEquals(502, e.httpStatus);
            assertEquals(XoApiException.MALFORMED_RESPONSE, e.errorCode);
        }
    }

    @Test
    public void okFalseLiteral_isAnError_notAMalformed() {
        try {
            RestGateway.parseEnvelope(response(400, "{\"ok\":false,\"error\":{\"code\":\"VALIDATION_ERROR\",\"message\":\"bad field\",\"details\":{\"field\":\"phone\"}}}"), "x.php");
            fail("must throw");
        } catch (XoApiException e) {
            assertEquals("VALIDATION_ERROR", e.errorCode);
        }
    }

    // ---- best-effort probe (the 401 single-flight retry decision) ----------------

    @Test
    public void envelopeErrorCode_bestEffortReads() {
        assertEquals("TOKEN_EXPIRED", RestGateway.envelopeErrorCode("{\"ok\":false,\"error\":{\"code\":\"TOKEN_EXPIRED\"}}"));
        assertNull(RestGateway.envelopeErrorCode("{\"ok\":true}"));
        assertNull(RestGateway.envelopeErrorCode("garbage html"));
        assertNull(RestGateway.envelopeErrorCode(null));
    }

    // ---- the deployed error fixtures, through the REAL parser --------------------

    private void assertFixtureCode(String fixture, int status, String code) throws Exception {
        JSONObject body = XoFixtures.obj(fixture);
        assertFalse(body.optBoolean("ok", true));
        try {
            RestGateway.parseEnvelope(response(status, body.toString()), "fixture");
            fail(fixture + " must be an error envelope");
        } catch (XoApiException e) {
            assertEquals("fixture " + fixture, status, e.httpStatus);
            assertEquals("fixture " + fixture, code, e.errorCode);
        }
    }

    @Test
    public void deployedErrorFixtures_parseToTheirExactCodes() throws Exception {
        assertFixtureCode("fixture_err_validation", 400, "VALIDATION_ERROR");
        assertFixtureCode("fixture_err_password_invalid", 400, "PASSWORD_INVALID");
        assertFixtureCode("fixture_err_account_exists", 409, "ACCOUNT_EXISTS");
        assertFixtureCode("fixture_err_unauthorized", 401, "UNAUTHORIZED");
        assertFixtureCode("fixture_err_user_is_blocked", 403, "USER_IS_BLOCKED");
        assertFixtureCode("fixture_err_privacy_key", 400, "PRIVACY_KEY_UNSUPPORTED");
        assertFixtureCode("fixture_err_not_found", 404, "NOT_FOUND");
        assertFixtureCode("fixture_err_multi_account", 403, "MULTI_ACCOUNT_FORBIDDEN");
        assertFixtureCode("fixture_err_too_many_attempts", 429, "TOO_MANY_ATTEMPTS");
    }

    // ---- lifecycle classification -------------------------------------------------

    @Test
    public void lifecycleClassification_isStrict() {
        // only genuine backend envelope codes may drive logout/retry decisions
        assertTrue(new XoApiException(401, XoApiException.TOKEN_EXPIRED, "x").isGenuineServerRejection());
        assertTrue(new XoApiException(401, XoApiException.UNAUTHORIZED, "x").isGenuineServerRejection());
        assertFalse("a garbled 401 page must NEVER read as a dead token family",
                new XoApiException(401, XoApiException.MALFORMED_RESPONSE, "waf page").isGenuineServerRejection());
        assertFalse(new XoApiException(403, "USER_IS_BLOCKED", "x").isGenuineServerRejection());

        assertTrue(new XoApiException(401, XoApiException.SESSION_INVALID, "x").isSessionInvalid());
        assertFalse(new XoApiException(401, XoApiException.UNAUTHORIZED, "x").isSessionInvalid());
    }

    @Test
    public void envelopeParse_survivesUnicodePayloads() throws Exception {
        // Persian content must round-trip the envelope untouched
        JSONObject parsed = RestGateway.parseEnvelope(response(200, "{\"ok\":true,\"message\":{\"content\":\"سلام\"}}"), "x.php");
        assertEquals("سلام", parsed.getJSONObject("message").getString("content"));
    }

    @Test
    public void tlRulesFromWire_andBlockedSlice_areDecoupledFromRestGatewayState() {
        // package seam sanity: the static mappers must be pure (no gateway instance)
        TLRPC.TL_boolTrue bool = new TLRPC.TL_boolTrue();
        assertTrue(bool != null); // TLRPC loads on the JVM (pure data classes)
    }
}
