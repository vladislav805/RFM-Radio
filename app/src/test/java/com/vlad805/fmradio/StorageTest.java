package com.vlad805.fmradio;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.vlad805.fmradio.service.fm.implementation.NativeTunerController;

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
    private Storage storage;

    @Before
    public void setUp() {
        prefs = new InMemorySharedPreferences();
        storage = new Storage(prefs);
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
    public void typedGettersReturnExpectedDefaults() {
        assertEquals(C.PrefDefaultValue.TUNER_REGION, storage.getRegion());
        assertEquals(C.PrefDefaultValue.TUNER_SPACING, storage.getSpacing());
        assertEquals(C.PrefDefaultValue.TUNER_STEREO, storage.isStereoEnabled());
        assertEquals(C.PrefDefaultValue.TUNER_SOFT_MUTE, storage.isSoftMuteEnabled());
        assertEquals(C.PrefDefaultValue.TUNER_NOTCH_FILTER, storage.getNotchFilter());
        assertEquals(C.PrefDefaultValue.TUNER_ANTENNA, storage.getAntenna());
        assertEquals(C.PrefDefaultValue.TUNER_POWER_MODE, storage.isLowPowerMode());
        assertEquals(C.PrefDefaultValue.RDS_AUTO_AF, storage.isAutoAfEnabled());

        assertEquals(C.PrefDefaultValue.RECORDING_DIRECTORY, storage.getRecordingDirectory());
        assertEquals(C.PrefDefaultValue.RECORDING_FILENAME, storage.getRecordingFilename());
        assertEquals(C.PrefDefaultValue.RECORDING_FORMAT, storage.getRecordingFormat());
        assertEquals(C.PrefDefaultValue.RECORDING_SAVE_SRT, storage.isRecordingSaveSrt());
        assertEquals(C.PrefDefaultValue.RECORDING_SAVE_PAST, storage.isRecordingPreRollEnabled());
        assertEquals(C.PrefDefaultValue.RECORDING_SHOW_NOTIFY, storage.isRecordingShowNotify());

        assertEquals(C.PrefDefaultValue.APP_AUTO_STARTUP, storage.isAutoStartupEnabled());
        assertEquals(C.PrefDefaultValue.NOTIFICATION_SHOW_RDS, storage.isNotificationShowRds());
        assertEquals(C.PrefDefaultValue.NOTIFICATION_SEEK_BY_FAVORITES, storage.isNotificationSeekByFavorites());

        assertEquals(C.PrefDefaultValue.LAST_FREQUENCY, storage.getLastFrequency());
        assertEquals("", storage.getDetectedBackend());
        assertEquals(C.PrefDefaultValue.FAVORITES_LIST_CURRENT, storage.getCurrentFavoriteList());
        assertEquals(0, storage.getNativeBinaryVersion());
        assertEquals(0L, storage.getBinaryApkUpdateTime());
    }

    @Test
    public void uiBoundPreferencesReadStringAndBooleanValuesSafely() {
        // AndroidX ListPreference / EditTextPreference persist numeric options as Strings.
        prefs.edit()
                .putString(C.PrefKey.TUNER_REGION, "4")
                .putString(C.PrefKey.TUNER_SPACING, "1")
                .putBoolean(C.PrefKey.TUNER_STEREO, false)
                .putBoolean(C.PrefKey.TUNER_SOFT_MUTE, false)
                .putString(C.PrefKey.TUNER_NOTCH_FILTER, "2")
                .putString(C.PrefKey.TUNER_ANTENNA, " 1 ")
                .putBoolean(C.PrefKey.TUNER_POWER_MODE, true)
                .putBoolean(C.PrefKey.RDS_AUTO_AF, true)
                .putString(C.PrefKey.RECORDING_DIRECTORY, "custom/$y")
                .putString(C.PrefKey.RECORDING_FILENAME, "rec-$f")
                .putString(C.PrefKey.RECORDING_FORMAT, String.valueOf(C.RecordFormat.MP3_128))
                .putBoolean(C.PrefKey.RECORDING_SAVE_SRT, true)
                .putBoolean(C.PrefKey.RECORDING_SAVE_PAST, true)
                .putBoolean(C.PrefKey.RECORDING_SHOW_NOTIFY, false)
                .putBoolean(C.PrefKey.APP_AUTO_STARTUP, true)
                .putBoolean(C.PrefKey.NOTIFICATION_SHOW_RDS, false)
                .putBoolean(C.PrefKey.NOTIFICATION_SEEK_BY_FAVORITES, true)
                .apply();

        assertEquals(4, storage.getRegion());
        assertEquals(1, storage.getSpacing());
        assertFalse(storage.isStereoEnabled());
        assertFalse(storage.isSoftMuteEnabled());
        assertEquals(2, storage.getNotchFilter());
        assertEquals(1, storage.getAntenna());
        assertTrue(storage.isLowPowerMode());
        assertTrue(storage.isAutoAfEnabled());

        assertEquals("custom/$y", storage.getRecordingDirectory());
        assertEquals("rec-$f", storage.getRecordingFilename());
        assertEquals(C.RecordFormat.MP3_128, storage.getRecordingFormat());
        assertTrue(storage.isRecordingSaveSrt());
        assertTrue(storage.isRecordingPreRollEnabled());
        assertFalse(storage.isRecordingShowNotify());

        assertTrue(storage.isAutoStartupEnabled());
        assertFalse(storage.isNotificationShowRds());
        assertTrue(storage.isNotificationSeekByFavorites());

        // setRegion must persist as String so AndroidX ListPreference never throws ClassCastException.
        storage.setRegion(2);
        assertEquals("2", prefs.getString(C.PrefKey.TUNER_REGION, null));
        assertEquals(2, storage.getRegion());
    }

    @Test
    public void uiBoundGettersRecoverSafelyFromMalformedOrMismatchedTypes() {
        prefs.edit().putString(C.PrefKey.TUNER_ANTENNA, "").apply();
        assertEquals(C.PrefDefaultValue.TUNER_ANTENNA, storage.getAntenna());

        prefs.edit().putString(C.PrefKey.TUNER_ANTENNA, "bad").apply();
        assertEquals(C.PrefDefaultValue.TUNER_ANTENNA, storage.getAntenna());

        prefs.edit().putInt(C.PrefKey.TUNER_REGION, 3).apply();
        assertEquals(3, storage.getRegion());

        prefs.edit().putBoolean(C.PrefKey.TUNER_SPACING, true).apply();
        assertEquals(C.PrefDefaultValue.TUNER_SPACING, storage.getSpacing());

        // Storing a String "true" under a boolean key whose default is false must fall back to false.
        prefs.edit().putString(C.PrefKey.RDS_AUTO_AF, "true").apply();
        assertFalse(storage.isAutoAfEnabled());
    }

    @Test
    public void internalNativeTypesRoundTripAndGuardTypeMismatches() {
        storage.setLastFrequency(101500);
        assertEquals(101500, prefs.getInt(C.PrefKey.LAST_FREQUENCY, 0));
        assertEquals(101500, storage.getLastFrequency());

        storage.setNativeBinaryVersion(900);
        assertEquals(900, prefs.getInt(C.PrefKey.BINARY_VERSION_NATIVE, 0));
        assertEquals(900, storage.getNativeBinaryVersion());

        storage.setBinaryApkUpdateTime(1730000000000L);
        assertEquals(1730000000000L, prefs.getLong(C.PrefKey.BINARY_APK_UPDATE_TIME, 0L));
        assertEquals(1730000000000L, storage.getBinaryApkUpdateTime());

        storage.setDetectedBackend("V4L2_EXYNOS");
        assertEquals("V4L2_EXYNOS", storage.getDetectedBackend());

        storage.setCurrentFavoriteList("rock");
        assertEquals("rock", storage.getCurrentFavoriteList());

        // Mismatched types do not throw ClassCastException.
        prefs.edit().putString(C.PrefKey.LAST_FREQUENCY, "99800").apply();
        assertEquals(99800, storage.getLastFrequency());

        prefs.edit().putBoolean(C.PrefKey.LAST_FREQUENCY, false).apply();
        assertEquals(C.PrefDefaultValue.LAST_FREQUENCY, storage.getLastFrequency());

        prefs.edit().putString(C.PrefKey.BINARY_APK_UPDATE_TIME, "bad").apply();
        assertEquals(0L, storage.getBinaryApkUpdateTime());

        prefs.edit().putInt(C.PrefKey.DETECTED_BACKEND, 123).apply();
        assertEquals("", storage.getDetectedBackend());

        prefs.edit().putInt(C.PrefKey.FAVORITES_LIST_CURRENT, 456).apply();
        assertEquals(C.PrefDefaultValue.FAVORITES_LIST_CURRENT, storage.getCurrentFavoriteList());
    }

    @Test
    public void identifiesTunerPreferenceKeysForServiceListenerFiltering() {
        assertTrue(NativeTunerController.isTunerPreferenceKey(C.PrefKey.TUNER_REGION));
        assertTrue(NativeTunerController.isTunerPreferenceKey(C.PrefKey.TUNER_SPACING));
        assertTrue(NativeTunerController.isTunerPreferenceKey(C.PrefKey.TUNER_STEREO));
        assertTrue(NativeTunerController.isTunerPreferenceKey(C.PrefKey.TUNER_SOFT_MUTE));
        assertTrue(NativeTunerController.isTunerPreferenceKey(C.PrefKey.TUNER_NOTCH_FILTER));
        assertTrue(NativeTunerController.isTunerPreferenceKey(C.PrefKey.TUNER_ANTENNA));
        assertTrue(NativeTunerController.isTunerPreferenceKey(C.PrefKey.TUNER_POWER_MODE));
        assertTrue(NativeTunerController.isTunerPreferenceKey(C.PrefKey.RDS_AUTO_AF));

        // Internal and non-tuner keys must not trigger setupTunerByPreferences.
        assertFalse(NativeTunerController.isTunerPreferenceKey(null));
        assertFalse(NativeTunerController.isTunerPreferenceKey(C.PrefKey.LAST_FREQUENCY));
        assertFalse(NativeTunerController.isTunerPreferenceKey(C.PrefKey.DETECTED_BACKEND));
        assertFalse(NativeTunerController.isTunerPreferenceKey(C.PrefKey.BINARY_VERSION_NATIVE));
        assertFalse(NativeTunerController.isTunerPreferenceKey(C.PrefKey.BINARY_APK_UPDATE_TIME));
        assertFalse(NativeTunerController.isTunerPreferenceKey(C.PrefKey.FAVORITES_LIST_CURRENT));
        assertFalse(NativeTunerController.isTunerPreferenceKey(C.PrefKey.RECORDING_SAVE_PAST));
        assertFalse(NativeTunerController.isTunerPreferenceKey(C.PrefKey.RECORDING_FORMAT));
        assertFalse(NativeTunerController.isTunerPreferenceKey(C.PrefKey.NOTIFICATION_SHOW_RDS));
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
