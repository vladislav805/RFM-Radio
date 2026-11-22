package com.vlad805.fmradio;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StorageTest {
    private InMemorySharedPreferences prefs;

    @Before
    public void setUp() {
        prefs = new InMemorySharedPreferences();
    }

    @Test
    public void parsePrefIntHandlesValidBlankAndInvalidStrings() {
        assertEquals(2, Storage.parsePrefInt("2", 1));
        assertEquals(0, Storage.parsePrefInt(" 0 ", 3));
        assertEquals(-5, Storage.parsePrefInt("-5", 1));
        assertEquals(1, Storage.parsePrefInt(null, 1));
        assertEquals(1, Storage.parsePrefInt("", 1));
        assertEquals(1, Storage.parsePrefInt("   ", 1));
        assertEquals(1, Storage.parsePrefInt("abc", 1));
        assertEquals(1, Storage.parsePrefInt("999999999999", 1));
    }

    @Test
    public void validatesNonNegativeIntegers() {
        assertTrue(Storage.isValidNonNegativeInt("0"));
        assertTrue(Storage.isValidNonNegativeInt("1"));
        assertTrue(Storage.isValidNonNegativeInt(" 2 "));
        assertFalse(Storage.isValidNonNegativeInt("-1"));
        assertFalse(Storage.isValidNonNegativeInt(""));
        assertFalse(Storage.isValidNonNegativeInt("   "));
        assertFalse(Storage.isValidNonNegativeInt(null));
        assertFalse(Storage.isValidNonNegativeInt("abc"));
        assertFalse(Storage.isValidNonNegativeInt("999999999999"));
    }

    @Test
    public void uiBoundNumericPreferencesUseDefaultsAndStringStorage() {
        assertEquals(
                C.PrefDefaultValue.TUNER_REGION,
                Storage.getPrefInt(prefs, C.PrefKey.TUNER_REGION, C.PrefDefaultValue.TUNER_REGION)
        );
        assertEquals(
                C.PrefDefaultValue.TUNER_SPACING,
                Storage.getPrefInt(prefs, C.PrefKey.TUNER_SPACING, C.PrefDefaultValue.TUNER_SPACING)
        );
        assertEquals(
                C.PrefDefaultValue.TUNER_NOTCH_FILTER,
                Storage.getPrefInt(prefs, C.PrefKey.TUNER_NOTCH_FILTER, C.PrefDefaultValue.TUNER_NOTCH_FILTER)
        );
        assertEquals(
                C.PrefDefaultValue.TUNER_ANTENNA,
                Storage.getPrefInt(prefs, C.PrefKey.TUNER_ANTENNA, C.PrefDefaultValue.TUNER_ANTENNA)
        );
        assertEquals(
                C.PrefDefaultValue.RECORDING_FORMAT,
                Storage.getPrefInt(prefs, C.PrefKey.RECORDING_FORMAT, C.PrefDefaultValue.RECORDING_FORMAT)
        );

        // AndroidX ListPreference / EditTextPreference persist numeric options as Strings.
        prefs.edit()
                .putString(C.PrefKey.TUNER_REGION, "4")
                .putString(C.PrefKey.TUNER_SPACING, "1")
                .putString(C.PrefKey.TUNER_NOTCH_FILTER, "2")
                .putString(C.PrefKey.TUNER_ANTENNA, " 1 ")
                .putString(C.PrefKey.RECORDING_FORMAT, String.valueOf(C.RecordFormat.MP3_128))
                .apply();

        assertEquals(4, Storage.getPrefInt(prefs, C.PrefKey.TUNER_REGION, C.PrefDefaultValue.TUNER_REGION));
        assertEquals(1, Storage.getPrefInt(prefs, C.PrefKey.TUNER_SPACING, C.PrefDefaultValue.TUNER_SPACING));
        assertEquals(2, Storage.getPrefInt(prefs, C.PrefKey.TUNER_NOTCH_FILTER, C.PrefDefaultValue.TUNER_NOTCH_FILTER));
        assertEquals(1, Storage.getPrefInt(prefs, C.PrefKey.TUNER_ANTENNA, C.PrefDefaultValue.TUNER_ANTENNA));
        assertEquals(
                C.RecordFormat.MP3_128,
                Storage.getPrefInt(prefs, C.PrefKey.RECORDING_FORMAT, C.PrefDefaultValue.RECORDING_FORMAT)
        );

        // putPrefInt must persist as String so AndroidX ListPreference never throws ClassCastException.
        Storage.putPrefInt(prefs, C.PrefKey.TUNER_REGION, 2);
        assertEquals("2", prefs.getString(C.PrefKey.TUNER_REGION, null));
        assertEquals(2, Storage.getPrefInt(prefs, C.PrefKey.TUNER_REGION, C.PrefDefaultValue.TUNER_REGION));
    }

    @Test
    public void getPrefIntRecoversSafelyFromMalformedOrMismatchedTypes() {
        prefs.edit().putString(C.PrefKey.TUNER_ANTENNA, "").apply();
        assertEquals(0, Storage.getPrefInt(prefs, C.PrefKey.TUNER_ANTENNA, 0));

        prefs.edit().putString(C.PrefKey.TUNER_ANTENNA, "bad").apply();
        assertEquals(0, Storage.getPrefInt(prefs, C.PrefKey.TUNER_ANTENNA, 0));

        prefs.edit().putInt(C.PrefKey.TUNER_REGION, 3).apply();
        assertEquals(3, Storage.getPrefInt(prefs, C.PrefKey.TUNER_REGION, 1));

        prefs.edit().putBoolean(C.PrefKey.TUNER_SPACING, true).apply();
        assertEquals(2, Storage.getPrefInt(prefs, C.PrefKey.TUNER_SPACING, 2));
    }

    @Test
    public void internalNativeTypesRoundTripAndGuardTypeMismatches() {
        assertEquals(
                C.PrefDefaultValue.LAST_FREQUENCY,
                Storage.getInt(prefs, C.PrefKey.LAST_FREQUENCY, C.PrefDefaultValue.LAST_FREQUENCY)
        );
        Storage.putInt(prefs, C.PrefKey.LAST_FREQUENCY, 101500);
        assertEquals(101500, prefs.getInt(C.PrefKey.LAST_FREQUENCY, 0));
        assertEquals(101500, Storage.getInt(prefs, C.PrefKey.LAST_FREQUENCY, 0));

        Storage.putLong(prefs, C.PrefKey.BINARY_APK_UPDATE_TIME, 1730000000000L);
        assertEquals(1730000000000L, prefs.getLong(C.PrefKey.BINARY_APK_UPDATE_TIME, 0L));
        assertEquals(1730000000000L, Storage.getLong(prefs, C.PrefKey.BINARY_APK_UPDATE_TIME, 0L));

        Storage.putString(prefs, C.PrefKey.DETECTED_BACKEND, "V4L2_EXYNOS");
        assertEquals("V4L2_EXYNOS", Storage.getPrefString(prefs, C.PrefKey.DETECTED_BACKEND, ""));

        Storage.putBoolean(prefs, C.PrefKey.RECORDING_SAVE_PAST, true);
        assertTrue(Storage.getPrefBoolean(prefs, C.PrefKey.RECORDING_SAVE_PAST, false));

        // Mismatched types do not throw ClassCastException.
        prefs.edit().putString(C.PrefKey.LAST_FREQUENCY, "99800").apply();
        assertEquals(99800, Storage.getInt(prefs, C.PrefKey.LAST_FREQUENCY, 87500));

        prefs.edit().putBoolean(C.PrefKey.LAST_FREQUENCY, false).apply();
        assertEquals(87500, Storage.getInt(prefs, C.PrefKey.LAST_FREQUENCY, 87500));

        prefs.edit().putString(C.PrefKey.BINARY_APK_UPDATE_TIME, "bad").apply();
        assertEquals(42L, Storage.getLong(prefs, C.PrefKey.BINARY_APK_UPDATE_TIME, 42L));

        prefs.edit().putInt(C.PrefKey.DETECTED_BACKEND, 123).apply();
        assertEquals("fallback", Storage.getPrefString(prefs, C.PrefKey.DETECTED_BACKEND, "fallback"));

        prefs.edit().putString(C.PrefKey.TUNER_STEREO, "true").apply();
        assertTrue(Storage.getPrefBoolean(prefs, C.PrefKey.TUNER_STEREO, true));
    }

    /** Minimal SharedPreferences fake mirroring Android's ClassCastException semantics. */
    private static final class InMemorySharedPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return Collections.unmodifiableMap(new HashMap<>(values));
        }

        @Nullable
        @Override
        public String getString(final String key, @Nullable final String defValue) {
            final Object v = values.get(key);
            return v != null ? (String) v : defValue;
        }

        @Nullable
        @Override
        @SuppressWarnings("unchecked")
        public Set<String> getStringSet(final String key, @Nullable final Set<String> defValues) {
            final Object v = values.get(key);
            return v != null ? new HashSet<>((Set<String>) v) : defValues;
        }

        @Override
        public int getInt(final String key, final int defValue) {
            final Object v = values.get(key);
            return v != null ? (Integer) v : defValue;
        }

        @Override
        public long getLong(final String key, final long defValue) {
            final Object v = values.get(key);
            return v != null ? (Long) v : defValue;
        }

        @Override
        public float getFloat(final String key, final float defValue) {
            final Object v = values.get(key);
            return v != null ? (Float) v : defValue;
        }

        @Override
        public boolean getBoolean(final String key, final boolean defValue) {
            final Object v = values.get(key);
            return v != null ? (Boolean) v : defValue;
        }

        @Override
        public boolean contains(final String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new InMemoryEditor(values);
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(final OnSharedPreferenceChangeListener listener) {}

        @Override
        public void unregisterOnSharedPreferenceChangeListener(final OnSharedPreferenceChangeListener listener) {}
    }

    private static final class InMemoryEditor implements SharedPreferences.Editor {
        private final Map<String, Object> target;
        private final Map<String, Object> staged = new HashMap<>();
        private final Set<String> removals = new HashSet<>();
        private boolean clear = false;

        InMemoryEditor(final Map<String, Object> target) {
            this.target = target;
        }

        @Override
        public SharedPreferences.Editor putString(final String key, @Nullable final String value) {
            staged.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putStringSet(final String key, @Nullable final Set<String> values) {
            staged.put(key, values != null ? new HashSet<>(values) : null);
            return this;
        }

        @Override
        public SharedPreferences.Editor putInt(final String key, final int value) {
            staged.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putLong(final String key, final long value) {
            staged.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putFloat(final String key, final float value) {
            staged.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putBoolean(final String key, final boolean value) {
            staged.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor remove(final String key) {
            removals.add(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor clear() {
            clear = true;
            return this;
        }

        @Override
        public boolean commit() {
            apply();
            return true;
        }

        @Override
        public void apply() {
            if (clear) {
                target.clear();
            }
            for (final String key : removals) {
                target.remove(key);
            }
            for (final Map.Entry<String, Object> entry : staged.entrySet()) {
                if (entry.getValue() == null) {
                    target.remove(entry.getKey());
                } else {
                    target.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }
}
