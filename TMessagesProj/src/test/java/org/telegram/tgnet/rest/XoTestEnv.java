package org.telegram.tgnet.rest;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import org.telegram.messenger.BuildVars;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * T63 test environment for JVM (local unit) tests.
 *
 * <p>The contract classes under test touch two android runtime facilities
 * that do not exist on the JVM:
 * <ul>
 *   <li>{@code FileLog} — silenced via {@link BuildVars#LOGS_ENABLED} = false
 *       (a public static, exactly what the app flips in release builds);</li>
 *   <li>{@code RestFileBridge}'s SharedPreferences-backed persistence — served
 *       an in-memory fake through a {@link ContextWrapper} planted as
 *       {@code ApplicationLoader.applicationContext}. Only
 *       {@code getSharedPreferences} is implemented; every other Context
 *       method delegates to a null base and must never be called on these
 *       paths (a call would NPE and fail the test loudly — by design).</li>
 * </ul>
 */
public final class XoTestEnv {

    private static boolean initialized;

    private XoTestEnv() {
    }

    /** Idempotent; call from every test class's {@code @BeforeClass}. */
    public static synchronized void init() {
        if (initialized) {
            return;
        }
        BuildVars.DEBUG_VERSION = false;
        BuildVars.LOGS_ENABLED = false;
        if (org.telegram.messenger.ApplicationLoader.applicationContext == null) {
            org.telegram.messenger.ApplicationLoader.applicationContext = new FakeAppContext();
        }
        initialized = true;
    }

    /** Reads a private static field (test introspection of the meta caches). */
    public static Object readStaticField(Class<?> clazz, String name) throws Exception {
        java.lang.reflect.Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    /** Writes a private static field (test reset of clock anchors etc.). */
    public static void writeStaticField(Class<?> clazz, String name, Object value) throws Exception {
        java.lang.reflect.Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    /** Context that only knows in-memory SharedPreferences. */
    public static final class FakeAppContext extends ContextWrapper {
        public FakeAppContext() {
            super((Context) null);
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return FakeSharedPreferences.forName(name);
        }
    }

    /** Minimal in-memory SharedPreferences (no listeners, apply == commit). */
    public static final class FakeSharedPreferences implements SharedPreferences {
        private static final Map<String, FakeSharedPreferences> NAMED = new HashMap<>();

        public static FakeSharedPreferences forName(String name) {
            synchronized (NAMED) {
                FakeSharedPreferences prefs = NAMED.get(name);
                if (prefs == null) {
                    prefs = new FakeSharedPreferences();
                    NAMED.put(name, prefs);
                }
                return prefs;
            }
        }

        private final Map<String, Object> values = new HashMap<>();
        private final FakeEditor editor = new FakeEditor();

        @Override
        public Map<String, ?> getAll() {
            synchronized (values) {
                return new HashMap<>(values);
            }
        }

        @Override
        public String getString(String key, String defValue) {
            synchronized (values) {
                Object v = values.get(key);
                return v instanceof String ? (String) v : defValue;
            }
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            return defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            synchronized (values) {
                Object v = values.get(key);
                return v instanceof Integer ? (Integer) v : defValue;
            }
        }

        @Override
        public long getLong(String key, long defValue) {
            synchronized (values) {
                Object v = values.get(key);
                return v instanceof Long ? (Long) v : defValue;
            }
        }

        @Override
        public float getFloat(String key, float defValue) {
            return defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            synchronized (values) {
                Object v = values.get(key);
                return v instanceof Boolean ? (Boolean) v : defValue;
            }
        }

        @Override
        public boolean contains(String key) {
            synchronized (values) {
                return values.containsKey(key);
            }
        }

        @Override
        public Editor edit() {
            return editor;
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        private void put(String key, Object value) {
            synchronized (values) {
                if (value == null) {
                    values.remove(key);
                } else {
                    values.put(key, value);
                }
            }
        }

        private final class FakeEditor implements Editor {
            @Override
            public Editor putString(String key, String value) {
                put(key, value);
                return this;
            }

            @Override
            public Editor putStringSet(String key, Set<String> values) {
                put(key, values == null ? null : new HashSet<>(values));
                return this;
            }

            @Override
            public Editor putInt(String key, int value) {
                put(key, value);
                return this;
            }

            @Override
            public Editor putLong(String key, long value) {
                put(key, value);
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                put(key, value);
                return this;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                put(key, value);
                return this;
            }

            @Override
            public Editor remove(String key) {
                put(key, null);
                return this;
            }

            @Override
            public Editor clear() {
                synchronized (values) {
                    values.clear();
                }
                return this;
            }

            @Override
            public boolean commit() {
                return true;
            }

            @Override
            public void apply() {
            }
        }
    }
}
