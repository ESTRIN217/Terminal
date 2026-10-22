package com.termux.shared.termux.settings.preferences;

import android.content.SharedPreferences;

import com.termux.shared.termux.TermuxConstants;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Unit tests for llvmpipe multithreading preferences (spec 012, RF-2, NFR-3).
 */
public class TermuxAppSharedPreferencesTest {

    private FakeSharedPreferences mFakePreferences;
    private TermuxAppSharedPreferences mPrefs;

    @Before
    public void setUp() {
        mFakePreferences = new FakeSharedPreferences();
        mPrefs = new TermuxAppSharedPreferences(mFakePreferences);
    }

    @Test
    public void testConstants_keysMatchSpec() {
        Assert.assertEquals("llvmpipe_threads_enabled", TermuxConstants.KEY_LLVMPIPE_THREADS_ENABLED);
        Assert.assertEquals("llvmpipe_threads_count", TermuxConstants.KEY_LLVMPIPE_THREADS_COUNT);
        Assert.assertEquals("llvmpipe_threads_enabled", TermuxPreferenceConstants.TERMUX_APP.KEY_LLVMPIPE_THREADS_ENABLED);
        Assert.assertEquals("llvmpipe_threads_count", TermuxPreferenceConstants.TERMUX_APP.KEY_LLVMPIPE_THREADS_COUNT);
        Assert.assertTrue(TermuxPreferenceConstants.TERMUX_APP.DEFAULT_VALUE_LLVMPIPE_THREADS_ENABLED);
        Assert.assertEquals(8, TermuxPreferenceConstants.TERMUX_APP.DEFAULT_VALUE_LLVMPIPE_THREADS_COUNT);
    }

    @Test
    public void testDefaults_enabledIsTrueAndCountIs8() {
        Assert.assertTrue(mPrefs.isLlvmpipeThreadsEnabled());
        Assert.assertEquals(8, mPrefs.getLlvmpipeThreadsCount());
    }

    @Test
    public void testRoundTrip_setEnabledAndCount() {
        mPrefs.setLlvmpipeThreadsEnabled(false);
        Assert.assertFalse(mPrefs.isLlvmpipeThreadsEnabled());

        mPrefs.setLlvmpipeThreadsCount(4);
        Assert.assertEquals(4, mPrefs.getLlvmpipeThreadsCount());

        mPrefs.setLlvmpipeThreadsEnabled(true);
        Assert.assertTrue(mPrefs.isLlvmpipeThreadsEnabled());

        mPrefs.setLlvmpipeThreadsCount(16);
        Assert.assertEquals(16, mPrefs.getLlvmpipeThreadsCount());
    }

    @Test
    public void testExistingKeysUntouched() {
        // NFR-3 / gotcha spec 008: existing keys in preferences are never deleted or corrupted.
        mFakePreferences.mData.put("existing_key", "existing_value");
        mFakePreferences.mData.put("sort_option", "name");

        mPrefs.setLlvmpipeThreadsEnabled(false);
        mPrefs.setLlvmpipeThreadsCount(12);

        Assert.assertEquals("existing_value", mFakePreferences.mData.get("existing_key"));
        Assert.assertEquals("name", mFakePreferences.mData.get("sort_option"));
        Assert.assertEquals(Boolean.FALSE, mFakePreferences.mData.get(TermuxConstants.KEY_LLVMPIPE_THREADS_ENABLED));
        Assert.assertEquals(12, mFakePreferences.mData.get(TermuxConstants.KEY_LLVMPIPE_THREADS_COUNT));
    }

    /** Minimal in-memory FakeSharedPreferences for unit testing. */
    private static class FakeSharedPreferences implements SharedPreferences {
        final Map<String, Object> mData = new HashMap<>();

        @Override
        public Map<String, ?> getAll() { return new HashMap<>(mData); }

        @Override
        public String getString(String key, String defValue) {
            Object v = mData.get(key);
            return (v instanceof String) ? (String) v : defValue;
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) { return defValues; }

        @Override
        public int getInt(String key, int defValue) {
            Object v = mData.get(key);
            return (v instanceof Integer) ? (Integer) v : defValue;
        }

        @Override
        public long getLong(String key, long defValue) { return defValue; }

        @Override
        public float getFloat(String key, float defValue) { return defValue; }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object v = mData.get(key);
            return (v instanceof Boolean) ? (Boolean) v : defValue;
        }

        @Override
        public boolean contains(String key) { return mData.containsKey(key); }

        @Override
        public Editor edit() { return new FakeEditor(mData); }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
    }

    private static class FakeEditor implements SharedPreferences.Editor {
        private final Map<String, Object> mTarget;
        private final Map<String, Object> mPending = new HashMap<>();

        FakeEditor(Map<String, Object> target) { mTarget = target; }

        @Override
        public SharedPreferences.Editor putString(String key, String value) { mPending.put(key, value); return this; }

        @Override
        public SharedPreferences.Editor putStringSet(String key, Set<String> values) { return this; }

        @Override
        public SharedPreferences.Editor putInt(String key, int value) { mPending.put(key, value); return this; }

        @Override
        public SharedPreferences.Editor putLong(String key, long value) { return this; }

        @Override
        public SharedPreferences.Editor putFloat(String key, float value) { return this; }

        @Override
        public SharedPreferences.Editor putBoolean(String key, boolean value) { mPending.put(key, value); return this; }

        @Override
        public SharedPreferences.Editor remove(String key) { mPending.put(key, this); return this; }

        @Override
        public SharedPreferences.Editor clear() { return this; }

        @Override
        public boolean commit() { apply(); return true; }

        @Override
        public void apply() {
            for (Map.Entry<String, Object> entry : mPending.entrySet()) {
                if (entry.getValue() == this) {
                    mTarget.remove(entry.getKey());
                } else {
                    mTarget.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }
}
