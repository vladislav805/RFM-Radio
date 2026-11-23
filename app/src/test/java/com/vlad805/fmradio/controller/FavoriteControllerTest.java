package com.vlad805.fmradio.controller;

import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.models.FavoriteFile;
import com.vlad805.fmradio.models.FavoriteStation;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FavoriteControllerTest {
    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    private Storage storage;
    private TestFavoriteController controller;

    @Before
    public void setUp() {
        storage = new Storage(new InMemorySharedPreferences());
        controller = new TestFavoriteController(tempFolder.getRoot(), storage);
    }

    @Test
    public void notifiesListenerAndUpdatesTitleCacheOnSaveAndRenameAndRemove() {
        final List<ChangeEvent> events = new ArrayList<>();
        final FavoriteController.OnFavoritesChangeListener listener =
                (listName, stations) -> events.add(new ChangeEvent(listName, stations));
        controller.addOnChangeListener(listener);

        final List<FavoriteStation> stations = controller.getStationsInCurrentList();
        stations.add(new FavoriteStation(101700, "Radio Rock"));
        stations.add(new FavoriteStation(98500, "Jazz FM"));
        controller.save();

        assertEquals(1, events.size());
        assertEquals(FavoriteController.DEFAULT_NAME, events.get(0).listName);
        assertEquals(2, events.get(0).stations.size());
        assertEquals("Radio Rock", controller.getFavoriteTitle(101700));
        assertEquals("Jazz FM", controller.getFavoriteTitle(98500));
        assertNull(controller.getFavoriteTitle(88000));

        // Rename a station
        stations.get(0).setTitle("Classic Rock");
        controller.save();
        assertEquals(2, events.size());
        assertEquals("Classic Rock", controller.getFavoriteTitle(101700));

        // Remove a station
        stations.remove(1);
        controller.save();
        assertEquals(3, events.size());
        assertNull(controller.getFavoriteTitle(98500));

        // Removing listener stops notifications
        controller.removeOnChangeListener(listener);
        stations.add(new FavoriteStation(104200, "Pop"));
        controller.save();
        assertEquals(3, events.size());
        assertEquals("Pop", controller.getFavoriteTitle(104200));
    }

    @Test
    public void replaceCurrentListStationsReplacesContentRebuildsCacheAndNotifies() {
        final List<ChangeEvent> events = new ArrayList<>();
        controller.addOnChangeListener((listName, stations) -> events.add(new ChangeEvent(listName, stations)));

        controller.getStationsInCurrentList().add(new FavoriteStation(87500, "Old Station"));
        controller.save();
        events.clear();

        controller.replaceCurrentListStations(Arrays.asList(
                new FavoriteStation(90100, "First"),
                new FavoriteStation(107900, "")
        ));

        assertEquals(1, events.size());
        assertEquals(2, events.get(0).stations.size());
        assertNull(controller.getFavoriteTitle(87500));
        assertEquals("First", controller.getFavoriteTitle(90100));
        assertEquals("", controller.getFavoriteTitle(107900));

        // Verify persistence across reload
        controller.reload();
        assertEquals(2, controller.getStationsInCurrentList().size());
        assertEquals("First", controller.getFavoriteTitle(90100));
    }

    @Test
    public void switchingRenamingAndRemovingListsUpdatesCacheAndNotifiesListeners() throws FileNotFoundException {
        final List<ChangeEvent> events = new ArrayList<>();
        controller.addOnChangeListener((listName, stations) -> events.add(new ChangeEvent(listName, stations)));

        // Populate default list
        controller.replaceCurrentListStations(Collections.singletonList(new FavoriteStation(100000, "DefaultOne")));
        assertEquals("DefaultOne", controller.getFavoriteTitle(100000));

        // Create and switch to second list
        controller.addList("city");
        events.clear();

        controller.setCurrentFavoriteList("city");
        assertEquals(1, events.size());
        assertEquals("city", events.get(0).listName);
        assertTrue(events.get(0).stations.isEmpty());
        assertNull(controller.getFavoriteTitle(100000));

        controller.replaceCurrentListStations(Collections.singletonList(new FavoriteStation(102500, "CityFM")));
        assertEquals("CityFM", controller.getFavoriteTitle(102500));

        // Rename active list
        events.clear();
        assertTrue(controller.renameList("city", "metro"));
        assertEquals("metro", controller.getCurrentFavoriteList());
        assertEquals("CityFM", controller.getFavoriteTitle(102500));
        assertEquals(1, events.size());
        assertEquals("metro", events.get(0).listName);

        // Remove active list -> falls back to default
        events.clear();
        assertTrue(controller.removeList("metro"));
        assertEquals(FavoriteController.DEFAULT_NAME, controller.getCurrentFavoriteList());
        assertEquals("DefaultOne", controller.getFavoriteTitle(100000));
        assertNull(controller.getFavoriteTitle(102500));
        assertEquals(1, events.size());
        assertEquals(FavoriteController.DEFAULT_NAME, events.get(0).listName);
    }

    private static final class ChangeEvent {
        final String listName;
        final List<FavoriteStation> stations;

        ChangeEvent(final String listName, final List<FavoriteStation> stations) {
            this.listName = listName;
            this.stations = stations;
        }
    }

    /**
     * Uses real files in TemporaryFolder with a simple line format so JVM unit tests
     * avoid Android's unmocked org.json stubs while still exercising file/list transitions.
     */
    private static final class TestFavoriteController extends FavoriteController {
        TestFavoriteController(@NonNull final File baseDir, @NonNull final Storage storage) {
            super(baseDir, storage);
        }

        @Override
        public FavoriteFile read() {
            final String content = readFile();
            final List<FavoriteStation> result = new ArrayList<>();
            if (content == null || content.trim().isEmpty() || content.trim().startsWith("{")) {
                return new FavoriteFile(result);
            }
            for (final String line : content.split("\n")) {
                if (line.isEmpty()) {
                    continue;
                }
                final int sep = line.indexOf('\t');
                if (sep <= 0) {
                    continue;
                }
                final int freq = Integer.parseInt(line.substring(0, sep));
                final String title = line.substring(sep + 1);
                result.add(new FavoriteStation(freq, title));
            }
            return new FavoriteFile(result);
        }

        @Override
        public void write(final FavoriteFile data) {
            final StringBuilder sb = new StringBuilder();
            if (data != null && data.getItems() != null) {
                for (final FavoriteStation station : data.getItems()) {
                    sb.append(station.getFrequency())
                            .append('\t')
                            .append(station.getTitle() != null ? station.getTitle() : "")
                            .append('\n');
                }
            }
            writeFile(sb.toString());
        }
    }

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
            return new Editor() {
                private final Map<String, Object> staged = new HashMap<>();
                private final Set<String> removals = new HashSet<>();
                private boolean clear = false;

                @Override
                public Editor putString(final String key, @Nullable final String value) {
                    staged.put(key, value);
                    return this;
                }

                @Override
                public Editor putStringSet(final String key, @Nullable final Set<String> vals) {
                    staged.put(key, vals != null ? new HashSet<>(vals) : null);
                    return this;
                }

                @Override
                public Editor putInt(final String key, final int value) {
                    staged.put(key, value);
                    return this;
                }

                @Override
                public Editor putLong(final String key, final long value) {
                    staged.put(key, value);
                    return this;
                }

                @Override
                public Editor putFloat(final String key, final float value) {
                    staged.put(key, value);
                    return this;
                }

                @Override
                public Editor putBoolean(final String key, final boolean value) {
                    staged.put(key, value);
                    return this;
                }

                @Override
                public Editor remove(final String key) {
                    removals.add(key);
                    return this;
                }

                @Override
                public Editor clear() {
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
                        values.clear();
                    }
                    for (final String key : removals) {
                        values.remove(key);
                    }
                    for (final Map.Entry<String, Object> entry : staged.entrySet()) {
                        if (entry.getValue() == null) {
                            values.remove(entry.getKey());
                        } else {
                            values.put(entry.getKey(), entry.getValue());
                        }
                    }
                }
            };
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(final OnSharedPreferenceChangeListener listener) {}

        @Override
        public void unregisterOnSharedPreferenceChangeListener(final OnSharedPreferenceChangeListener listener) {}
    }
}
