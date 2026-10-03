package org.telegram.tgnet.rest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * T63 — loads the frozen backend-output fixtures from src/test/resources.
 * Every fixture is a REAL JSON body produced by the deployed backend
 * controllers (backend test bed, tests/make_fixtures.php), so these tests
 * pin the client parser to what the server ACTUALLY answers — "client
 * follows backend outputs" as a build gate.
 */
public final class XoFixtures {

    private XoFixtures() {
    }

    /** One fixture object, e.g. obj("fixture_user_public") for fixtures/fixture_user_public.json. */
    public static JSONObject obj(String name) throws Exception {
        return new JSONObject(slurp(name));
    }

    /** One fixture array. */
    public static JSONArray arr(String name) throws Exception {
        return new JSONArray(slurp(name));
    }

    private static String slurp(String name) throws Exception {
        InputStream in = XoFixtures.class.getClassLoader()
                .getResourceAsStream("fixtures/" + name + ".json");
        if (in == null) {
            throw new IllegalStateException("fixture missing: fixtures/" + name + ".json");
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        } finally {
            in.close();
        }
    }
}
