package com.vlad805.fmradio;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.vlad805.fmradio.enums.AppTheme;
import com.vlad805.fmradio.service.fm.implementation.NativeTunerController;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class StorageTest {
    private static final String APP_NS = "http://schemas.android.com/apk/res-auto";
    private static final Set<String> NON_PERSISTED_XML_KEYS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("tuner_rds_description", "pref_info_version", "pref_about"))
    );

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
        assertEquals(Storage.Default.TUNER_REGION, storage.getRegion());
        assertEquals(Storage.Default.TUNER_SPACING, storage.getSpacing());
        assertEquals(Storage.Default.TUNER_STEREO, storage.isStereoEnabled());
        assertEquals(Storage.Default.TUNER_SOFT_MUTE, storage.isSoftMuteEnabled());
        assertEquals(Storage.Default.TUNER_NOTCH_FILTER, storage.getNotchFilter());
        assertEquals(Storage.Default.TUNER_ANTENNA, storage.getAntenna());
        assertEquals(Storage.Default.TUNER_POWER_MODE, storage.isLowPowerMode());
        assertEquals(Storage.Default.RDS_AUTO_AF, storage.isAutoAfEnabled());

        assertEquals(Storage.Default.RECORDING_DIRECTORY, storage.getRecordingDirectory());
        assertEquals(Storage.Default.RECORDING_FILENAME, storage.getRecordingFilename());
        assertEquals(Storage.Default.RECORDING_FORMAT, storage.getRecordingFormat());
        assertEquals(Storage.Default.RECORDING_SAVE_PAST, storage.isRecordingPreRollEnabled());
        assertEquals(Storage.Default.RECORDING_SHOW_NOTIFY, storage.isRecordingShowNotify());

        assertEquals(Storage.Default.APP_AUTO_STARTUP, storage.isAutoStartupEnabled());
        assertEquals(Storage.Default.APP_FAVORITES_COLUMNS, storage.getFavoritesColumns());
        assertEquals(Storage.Default.APP_THEME, storage.getAppTheme());
        assertEquals(Storage.Default.NOTIFICATION_SHOW_RDS, storage.isNotificationShowRds());
        assertEquals(Storage.Default.NOTIFICATION_SEEK_BY_FAVORITES, storage.isNotificationSeekByFavorites());

        assertEquals(Storage.Default.LAST_FREQUENCY, storage.getLastFrequency());
        assertEquals("", storage.getDetectedBackend());
        assertEquals(Storage.Default.FAVORITES_LIST_CURRENT, storage.getCurrentFavoriteList());
        assertEquals(0, storage.getNativeBinaryVersion());
        assertEquals(0L, storage.getBinaryApkUpdateTime());
    }

    @Test
    public void preferencesXmlKeysAndDefaultsMatchStorage() throws Exception {
        final Map<String, String> strings = parseStringResources(
                resolveAppResource("src/main/res/values/strings.xml")
        );
        final Map<String, String> xmlDefaults = new LinkedHashMap<>();
        final Set<String> seenNonPersistedKeys = new HashSet<>();

        final Document prefsDoc = parseXml(resolveAppResource("src/main/res/xml/preferences.xml"));
        collectPreferenceDefaults(prefsDoc.getDocumentElement(), strings, xmlDefaults, seenNonPersistedKeys);

        assertEquals(
                "Expected non-persisted info preferences in preferences.xml",
                NON_PERSISTED_XML_KEYS,
                seenNonPersistedKeys
        );

        final Map<String, String> storageDefaults = new LinkedHashMap<>();
        storageDefaults.put(Storage.Key.TUNER_REGION, String.valueOf(storage.getRegion()));
        storageDefaults.put(Storage.Key.TUNER_SPACING, String.valueOf(storage.getSpacing()));
        storageDefaults.put(Storage.Key.TUNER_STEREO, String.valueOf(storage.isStereoEnabled()));
        storageDefaults.put(Storage.Key.TUNER_SOFT_MUTE, String.valueOf(storage.isSoftMuteEnabled()));
        storageDefaults.put(Storage.Key.TUNER_NOTCH_FILTER, String.valueOf(storage.getNotchFilter()));
        storageDefaults.put(Storage.Key.TUNER_ANTENNA, String.valueOf(storage.getAntenna()));
        storageDefaults.put(Storage.Key.TUNER_POWER_MODE, String.valueOf(storage.isLowPowerMode()));
        storageDefaults.put(Storage.Key.RDS_AUTO_AF, String.valueOf(storage.isAutoAfEnabled()));
        storageDefaults.put(Storage.Key.RECORDING_DIRECTORY, storage.getRecordingDirectory());
        storageDefaults.put(Storage.Key.RECORDING_FILENAME, storage.getRecordingFilename());
        storageDefaults.put(Storage.Key.RECORDING_FORMAT, String.valueOf(storage.getRecordingFormat()));
        storageDefaults.put(Storage.Key.RECORDING_SAVE_PAST, String.valueOf(storage.isRecordingPreRollEnabled()));
        storageDefaults.put(Storage.Key.RECORDING_SHOW_NOTIFY, String.valueOf(storage.isRecordingShowNotify()));
        storageDefaults.put(Storage.Key.APP_AUTO_STARTUP, String.valueOf(storage.isAutoStartupEnabled()));
        storageDefaults.put(Storage.Key.APP_FAVORITES_COLUMNS, String.valueOf(storage.getFavoritesColumns()));
        storageDefaults.put(Storage.Key.APP_THEME, storage.getAppTheme().getPreferenceValue());
        storageDefaults.put(Storage.Key.NOTIFICATION_SHOW_RDS, String.valueOf(storage.isNotificationShowRds()));
        storageDefaults.put(
                Storage.Key.NOTIFICATION_SEEK_BY_FAVORITES,
                String.valueOf(storage.isNotificationSeekByFavorites())
        );

        assertEquals(
                "Persisted keys in preferences.xml must match Storage.Key.*",
                storageDefaults.keySet(),
                xmlDefaults.keySet()
        );
        for (final Map.Entry<String, String> entry : storageDefaults.entrySet()) {
            assertEquals(
                    "Default value mismatch between Storage and preferences.xml for key '" + entry.getKey() + "'",
                    entry.getValue(),
                    xmlDefaults.get(entry.getKey())
            );
        }
    }

    @Test
    public void uiBoundPreferencesReadStringAndBooleanValuesSafely() {
        // AndroidX ListPreference / EditTextPreference persist numeric options as Strings.
        prefs.edit()
                .putString(Storage.Key.TUNER_REGION, "4")
                .putString(Storage.Key.TUNER_SPACING, "1")
                .putBoolean(Storage.Key.TUNER_STEREO, false)
                .putBoolean(Storage.Key.TUNER_SOFT_MUTE, false)
                .putString(Storage.Key.TUNER_NOTCH_FILTER, "2")
                .putString(Storage.Key.TUNER_ANTENNA, " 1 ")
                .putBoolean(Storage.Key.TUNER_POWER_MODE, true)
                .putBoolean(Storage.Key.RDS_AUTO_AF, true)
                .putString(Storage.Key.RECORDING_DIRECTORY, "custom/$y")
                .putString(Storage.Key.RECORDING_FILENAME, "rec-$f")
                .putString(Storage.Key.RECORDING_FORMAT, String.valueOf(C.RecordFormat.MP3_128))
                .putBoolean(Storage.Key.RECORDING_SAVE_PAST, true)
                .putBoolean(Storage.Key.RECORDING_SHOW_NOTIFY, false)
                .putBoolean(Storage.Key.APP_AUTO_STARTUP, true)
                .putString(Storage.Key.APP_FAVORITES_COLUMNS, "2")
                .putString(Storage.Key.APP_THEME, AppTheme.DARK.getPreferenceValue())
                .putBoolean(Storage.Key.NOTIFICATION_SHOW_RDS, false)
                .putBoolean(Storage.Key.NOTIFICATION_SEEK_BY_FAVORITES, true)
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
        assertTrue(storage.isRecordingPreRollEnabled());
        assertFalse(storage.isRecordingShowNotify());

        assertTrue(storage.isAutoStartupEnabled());
        assertEquals(2, storage.getFavoritesColumns());
        assertEquals(AppTheme.DARK, storage.getAppTheme());
        assertFalse(storage.isNotificationShowRds());
        assertTrue(storage.isNotificationSeekByFavorites());

        // setRegion must persist as String so AndroidX ListPreference never throws ClassCastException.
        storage.setRegion(2);
        assertEquals("2", prefs.getString(Storage.Key.TUNER_REGION, null));
        assertEquals(2, storage.getRegion());
    }

    @Test
    public void uiBoundGettersRecoverSafelyFromMalformedOrMismatchedTypes() {
        prefs.edit().putString(Storage.Key.TUNER_ANTENNA, "").apply();
        assertEquals(Storage.Default.TUNER_ANTENNA, storage.getAntenna());

        prefs.edit().putString(Storage.Key.TUNER_ANTENNA, "bad").apply();
        assertEquals(Storage.Default.TUNER_ANTENNA, storage.getAntenna());

        prefs.edit().putInt(Storage.Key.TUNER_REGION, 3).apply();
        assertEquals(3, storage.getRegion());

        prefs.edit().putBoolean(Storage.Key.TUNER_SPACING, true).apply();
        assertEquals(Storage.Default.TUNER_SPACING, storage.getSpacing());

        // Storing a String "true" under a boolean key whose default is false must fall back to false.
        prefs.edit().putString(Storage.Key.RDS_AUTO_AF, "true").apply();
        assertFalse(storage.isAutoAfEnabled());
    }

    @Test
    public void internalNativeTypesRoundTripAndGuardTypeMismatches() {
        storage.setLastFrequency(101500);
        assertEquals(101500, prefs.getInt(Storage.KEY_LAST_FREQUENCY, 0));
        assertEquals(101500, storage.getLastFrequency());

        storage.setNativeBinaryVersion(900);
        assertEquals(900, prefs.getInt(Storage.KEY_BINARY_VERSION_NATIVE, 0));
        assertEquals(900, storage.getNativeBinaryVersion());

        storage.setBinaryApkUpdateTime(1730000000000L);
        assertEquals(1730000000000L, prefs.getLong(Storage.KEY_BINARY_APK_UPDATE_TIME, 0L));
        assertEquals(1730000000000L, storage.getBinaryApkUpdateTime());

        storage.setDetectedBackend("V4L2_EXYNOS");
        assertEquals("V4L2_EXYNOS", storage.getDetectedBackend());

        storage.setCurrentFavoriteList("rock");
        assertEquals("rock", storage.getCurrentFavoriteList());

        // Mismatched types do not throw ClassCastException.
        prefs.edit().putString(Storage.KEY_LAST_FREQUENCY, "99800").apply();
        assertEquals(99800, storage.getLastFrequency());

        prefs.edit().putBoolean(Storage.KEY_LAST_FREQUENCY, false).apply();
        assertEquals(Storage.Default.LAST_FREQUENCY, storage.getLastFrequency());

        prefs.edit().putString(Storage.KEY_BINARY_APK_UPDATE_TIME, "bad").apply();
        assertEquals(0L, storage.getBinaryApkUpdateTime());

        prefs.edit().putInt(Storage.KEY_DETECTED_BACKEND, 123).apply();
        assertEquals("", storage.getDetectedBackend());

        prefs.edit().putInt(Storage.KEY_FAVORITES_LIST_CURRENT, 456).apply();
        assertEquals(Storage.Default.FAVORITES_LIST_CURRENT, storage.getCurrentFavoriteList());
    }

    @Test
    public void identifiesTunerPreferenceKeysForServiceListenerFiltering() {
        assertTrue(NativeTunerController.isTunerPreferenceKey(Storage.Key.TUNER_REGION));
        assertTrue(NativeTunerController.isTunerPreferenceKey(Storage.Key.TUNER_SPACING));
        assertTrue(NativeTunerController.isTunerPreferenceKey(Storage.Key.TUNER_STEREO));
        assertTrue(NativeTunerController.isTunerPreferenceKey(Storage.Key.TUNER_SOFT_MUTE));
        assertTrue(NativeTunerController.isTunerPreferenceKey(Storage.Key.TUNER_NOTCH_FILTER));
        assertTrue(NativeTunerController.isTunerPreferenceKey(Storage.Key.TUNER_ANTENNA));
        assertTrue(NativeTunerController.isTunerPreferenceKey(Storage.Key.TUNER_POWER_MODE));
        assertTrue(NativeTunerController.isTunerPreferenceKey(Storage.Key.RDS_AUTO_AF));

        // Internal and non-tuner keys must not trigger setupTunerByPreferences.
        assertFalse(NativeTunerController.isTunerPreferenceKey(null));
        assertFalse(NativeTunerController.isTunerPreferenceKey(Storage.KEY_LAST_FREQUENCY));
        assertFalse(NativeTunerController.isTunerPreferenceKey(Storage.KEY_DETECTED_BACKEND));
        assertFalse(NativeTunerController.isTunerPreferenceKey(Storage.KEY_BINARY_VERSION_NATIVE));
        assertFalse(NativeTunerController.isTunerPreferenceKey(Storage.KEY_BINARY_APK_UPDATE_TIME));
        assertFalse(NativeTunerController.isTunerPreferenceKey(Storage.KEY_FAVORITES_LIST_CURRENT));
        assertFalse(NativeTunerController.isTunerPreferenceKey(Storage.Key.RECORDING_SAVE_PAST));
        assertFalse(NativeTunerController.isTunerPreferenceKey(Storage.Key.RECORDING_FORMAT));
        assertFalse(NativeTunerController.isTunerPreferenceKey(Storage.Key.NOTIFICATION_SHOW_RDS));
        assertFalse(NativeTunerController.isTunerPreferenceKey(Storage.Key.APP_FAVORITES_COLUMNS));
        assertFalse(NativeTunerController.isTunerPreferenceKey(Storage.Key.APP_THEME));
    }

    private static File resolveAppResource(final String relativePath) {
        final File fromModule = new File(relativePath);
        if (fromModule.isFile()) {
            return fromModule;
        }
        final File fromRoot = new File("app", relativePath);
        if (fromRoot.isFile()) {
            return fromRoot;
        }
        throw new AssertionError("Cannot locate resource file: " + relativePath
                + " (cwd=" + new File(".").getAbsolutePath() + ")");
    }

    private static Document parseXml(final File file) throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        final DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(file);
    }

    private static Map<String, String> parseStringResources(final File stringsXml) throws Exception {
        final Document doc = parseXml(stringsXml);
        final NodeList nodes = doc.getElementsByTagName("string");
        final Map<String, String> strings = new HashMap<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            final Element el = (Element) nodes.item(i);
            final String name = el.getAttribute("name");
            if (!name.isEmpty()) {
                strings.put(name, el.getTextContent());
            }
        }
        return strings;
    }

    private static void collectPreferenceDefaults(
            final Element parent,
            final Map<String, String> strings,
            final Map<String, String> xmlDefaults,
            final Set<String> seenNonPersistedKeys
    ) {
        final NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            final Element el = (Element) node;
            final String tag = el.getLocalName();
            if ("PreferenceCategory".equals(tag) || "PreferenceScreen".equals(tag)) {
                collectPreferenceDefaults(el, strings, xmlDefaults, seenNonPersistedKeys);
                continue;
            }

            final String key = el.getAttributeNS(APP_NS, "key");
            assertFalse("Preference element <" + tag + "> is missing app:key", key.isEmpty());

            if (NON_PERSISTED_XML_KEYS.contains(key)) {
                seenNonPersistedKeys.add(key);
                continue;
            }

            final String rawDefault = el.getAttributeNS(APP_NS, "defaultValue");
            assertFalse("Persisted preference '" + key + "' is missing app:defaultValue", rawDefault.isEmpty());

            final String resolvedDefault;
            if (rawDefault.startsWith("@string/")) {
                final String stringName = rawDefault.substring("@string/".length());
                resolvedDefault = strings.get(stringName);
                assertNotNull("Unresolved string reference '" + rawDefault + "' for key '" + key + "'", resolvedDefault);
            } else {
                resolvedDefault = rawDefault;
            }

            assertFalse("Duplicate key in preferences.xml: " + key, xmlDefaults.containsKey(key));
            xmlDefaults.put(key, resolvedDefault);
        }
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
