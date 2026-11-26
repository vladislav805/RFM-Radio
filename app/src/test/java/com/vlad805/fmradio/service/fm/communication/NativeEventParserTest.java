package com.vlad805.fmradio.service.fm.communication;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.vlad805.fmradio.controller.RadioState;
import com.vlad805.fmradio.controller.TunerStatus;
import com.vlad805.fmradio.models.RtPlusFields;
import com.vlad805.fmradio.service.fm.FMEventCallback;
import com.vlad805.fmradio.service.fm.RadioStatePatch;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NativeEventParserTest {
    private RecordingCallback callback;
    private NativeEventParser parser;

    @Before
    public void setUp() {
        callback = new RecordingCallback();
        parser = new NativeEventParser(callback);
    }

    @Test
    public void dispatchesValidStatePatchWithoutIntent() {
        final MapJsonObject rtPlus = new MapJsonObject()
                .put("ITEM.TITLE", "  Song Name\nLine2  ")
                .put("ITEM.ARTIST", "Artist")
                .put("ITEM.ALBUM", "Album");

        final MapJsonObject rds = new MapJsonObject()
                .put("ps", "  ROCK FM  ")
                .put("rt", "Now\nPlaying")
                .put("pi", "1234")
                .put("country", "UA")
                .put("pty", 11)
                .put("af", new ListJsonArray(101700, 102500))
                .put("rt_plus", rtPlus);

        final MapJsonObject message = new MapJsonObject()
                .put("type", "state")
                .put("frequency", 101700)
                .put("stereo", true)
                .put("rmssi", -68)
                .put("sinr", 15)
                .put("rds", rds);

        parser.handle(message);

        assertEquals(1, callback.patches.size());
        final RadioStatePatch patch = callback.patches.get(0);
        assertEquals(Integer.valueOf(101700), patch.getFrequency());
        assertEquals(Boolean.TRUE, patch.getStereo());
        assertEquals(Integer.valueOf(-68), patch.getRmssi());
        assertEquals(Integer.valueOf(15), patch.getSinr());
        assertEquals("ROCK FM", patch.getPs());
        assertEquals("Now Playing", patch.getRt());
        assertEquals("1234", patch.getPi());
        assertEquals("UA", patch.getCountry());
        assertEquals(Integer.valueOf(11), patch.getPty());
        assertArrayEquals(new int[]{101700, 102500}, patch.getAf());
        assertEquals("Song Name Line2", patch.getRtPlusFields().get("ITEM.TITLE"));
        assertEquals("Artist", patch.getRtPlusFields().get("ITEM.ARTIST"));
        assertEquals("Album", patch.getRtPlusFields().get("ITEM.ALBUM"));
    }

    @Test
    public void handlesNullSignalMetricsAsUnknownAndRejectsInvalidPayloads() {
        final MapJsonObject nullMetrics = new MapJsonObject()
                .put("type", "state")
                .put("rmssi", null)
                .put("sinr", null);
        parser.handle(nullMetrics);

        assertEquals(1, callback.patches.size());
        assertEquals(Integer.valueOf(RadioState.RMSSI_UNKNOWN), callback.patches.get(0).getRmssi());
        assertEquals(Integer.valueOf(RadioState.SINR_UNKNOWN), callback.patches.get(0).getSinr());

        // Invalid frequency drops patch
        parser.handle(new MapJsonObject().put("type", "state").put("frequency", 0));
        // Invalid country drops patch
        parser.handle(new MapJsonObject()
                .put("type", "state")
                .put("rds", new MapJsonObject().put("country", "bad")));
        assertEquals(1, callback.patches.size());
    }

    @Test
    public void dispatchesSearchCallbacksAndIgnoresLifecycleDuplicates() {
        parser.handle(new MapJsonObject().put("type", "enabled"));
        parser.handle(new MapJsonObject().put("type", "disabled"));
        parser.handle(new MapJsonObject().put("type", "seek_done"));

        assertTrue(callback.statuses.isEmpty());
        assertTrue(callback.patches.isEmpty());

        parser.handle(new MapJsonObject().put("type", "search_progress").put("count", 4));
        parser.handle(new MapJsonObject().put("type", "search_done").put("stations", new ListJsonArray(88000, 101700)));
        parser.handle(new MapJsonObject().put("type", "search_failed"));

        assertEquals(1, callback.searchProgressCounts.size());
        assertEquals(Integer.valueOf(4), callback.searchProgressCounts.get(0));
        assertEquals(1, callback.searchResults.size());
        assertArrayEquals(new int[]{88000, 101700}, callback.searchResults.get(0));
        assertEquals(1, callback.searchFailedCount);
    }

    @Test
    public void partialRtPlusObjectSetsOnlyPresentKeys() {
        final MapJsonObject message = new MapJsonObject()
                .put("type", "state")
                .put("rds", new MapJsonObject()
                        .put("rt_plus", new MapJsonObject().put("ITEM.TITLE", "Only Title")));

        parser.handle(message);

        assertEquals(1, callback.patches.size());
        final RadioStatePatch patch = callback.patches.get(0);
        assertEquals("Only Title", patch.getRtPlusFields().get("ITEM.TITLE"));
        assertNull(patch.getRtPlusFields().get("ITEM.ARTIST"));
        assertNull(patch.getRtPlusFields().get("ITEM.ALBUM"));
    }

    @Test
    public void emptyAndNullValuesClearOnlyTheirOwnFields() {
        final RadioState state = new RadioState();
        applyFields(state, new MapJsonObject().put("ITEM.TITLE", "Song").put("ITEM.ALBUM", "Album").put("ITEM.ARTIST", "Artist"));
        assertEquals(Map.of("ITEM.TITLE", "Song", "ITEM.ALBUM", "Album", "ITEM.ARTIST", "Artist"), state.getRtPlus().getFields());
        applyFields(state, new MapJsonObject().put("ITEM.TITLE", ""));
        assertEquals(Map.of("ITEM.ALBUM", "Album", "ITEM.ARTIST", "Artist"), state.getRtPlus().getFields());
        applyFields(state, new MapJsonObject().put("ITEM.ALBUM", null));
        assertEquals(Map.of("ITEM.ARTIST", "Artist"), state.getRtPlus().getFields());
    }

    @Test
    public void preservesTheWireNamesOfAllAssignedContentTypes() {
        final RadioState state = new RadioState();
        final MapJsonObject payload = new MapJsonObject();
        final Map<String, String> expected = new LinkedHashMap<>();
        for (String key : RtPlusFields.names()) {
            final String value = "Field " + key;
            payload.put(key, value);
            expected.put(key, value);
        }

        applyFields(state, payload);
        assertEquals(61, expected.size());
        assertEquals(expected, state.getRtPlus().getFields());
    }

    @Test
    public void unknownRtPlusKeysDoNotProduceAStatePatch() {
        final MapJsonObject fields = new MapJsonObject().put("UNKNOWN", "dummy").put("RESERVED", "reserved")
                .put("1", "numeric").put("25", "numeric")
                .put("title", "Song").put("album", "Album").put("artist", "Artist");
        parser.handle(new MapJsonObject().put("type", "state")
                .put("rds", new MapJsonObject().put("rt_plus", fields)));
        assertTrue(callback.patches.isEmpty());
    }

    @Test
    public void partialUpdatesMergeByContentTypeAndEmptyValuesRemoveRows() {
        final RadioState state = new RadioState();
        applyFields(state, new MapJsonObject().put("ITEM.ARTIST", "Artist").put("INFO.WEATHER", " Rain ")
                .put("RESERVED", "reserved").put("PRIVATE.56", "Station data").put("DESCRIPTOR.GET_DATA", " More\ninfo "));
        assertEquals(4, state.getRtPlus().getFields().size());
        assertEquals("More info", state.getRtPlus().getFields().get("DESCRIPTOR.GET_DATA"));
        applyFields(state, new MapJsonObject().put("ITEM.ARTIST", " ").put("INFO.WEATHER", "").put("PROGRAMME.HOMEPAGE", "fm.example"));
        assertEquals(3, state.getRtPlus().getFields().size());
        assertEquals("Station data", state.getRtPlus().getFields().get("PRIVATE.56"));
        assertNull(state.getRtPlus().getFields().get("INFO.WEATHER"));
        assertNull(state.getRtPlus().getFields().get("ITEM.ARTIST"));
        applyFields(state, new MapJsonObject());
        assertTrue(state.getRtPlus().isEmpty());
    }

    private void applyFields(final RadioState state, final MapJsonObject fields) {
        parser.handle(new MapJsonObject().put("type", "state")
                .put("rds", new MapJsonObject().put("rt_plus", fields)));
        state.applyPatch(callback.patches.get(callback.patches.size() - 1));
    }

    private static final class RecordingCallback implements FMEventCallback {
        final List<TunerStatus> statuses = new ArrayList<>();
        final List<RadioStatePatch> patches = new ArrayList<>();
        final List<Integer> searchProgressCounts = new ArrayList<>();
        final List<int[]> searchResults = new ArrayList<>();
        int searchFailedCount = 0;

        @Override
        public void onStatusChanged(@NonNull final TunerStatus status) {
            statuses.add(status);
        }

        @Override
        public void onFrequencyChanged(final int frequencyKhz) {}

        @Override
        public void onStatePatch(@NonNull final RadioStatePatch patch) {
            patches.add(patch);
        }

        @Override
        public void onSearchProgress(final int count) {
            searchProgressCounts.add(count);
        }

        @Override
        public void onSearchDone(@NonNull final int[] stations) {
            searchResults.add(stations);
        }

        @Override
        public void onSearchFailed() {
            searchFailedCount++;
        }

        @Override
        public void onInvalidAntennaError() {}

        @Override
        public void onError(@NonNull final String message) {}

        @Override
        public void onUnexpectedExit(final int exitCode) {}
    }

    private static final class MapJsonObject implements NativeEventParser.JsonObjectView {
        private final Map<String, Object> entries = new LinkedHashMap<>();

        MapJsonObject put(final String key, @Nullable final Object value) {
            entries.put(key, value);
            return this;
        }

        @Override
        public boolean has(final String key) {
            return entries.containsKey(key);
        }

        @Override
        public boolean isNull(final String key) {
            return entries.containsKey(key) && entries.get(key) == null;
        }

        @Override
        public String optString(final String key, final String fallback) {
            final Object val = entries.get(key);
            return val instanceof String ? (String) val : fallback;
        }

        @Override
        public int optInt(final String key, final int fallback) {
            final Object val = entries.get(key);
            return val instanceof Number ? ((Number) val).intValue() : fallback;
        }

        @Override
        public boolean optBoolean(final String key, final boolean fallback) {
            final Object val = entries.get(key);
            return val instanceof Boolean ? (Boolean) val : fallback;
        }

        @Nullable
        @Override
        public NativeEventParser.JsonObjectView optObject(final String key) {
            final Object val = entries.get(key);
            return val instanceof NativeEventParser.JsonObjectView
                    ? (NativeEventParser.JsonObjectView) val
                    : null;
        }

        @Nullable
        @Override
        public NativeEventParser.JsonArrayView optArray(final String key) {
            final Object val = entries.get(key);
            return val instanceof NativeEventParser.JsonArrayView
                    ? (NativeEventParser.JsonArrayView) val
                    : null;
        }

        @Override
        public Iterable<String> keys() {
            return entries.keySet();
        }

        @Override
        public int length() {
            return entries.size();
        }
    }

    private static final class ListJsonArray implements NativeEventParser.JsonArrayView {
        private final int[] items;

        ListJsonArray(final int... items) {
            this.items = items;
        }

        @Override
        public int length() {
            return items.length;
        }

        @Override
        public int optInt(final int index, final int fallback) {
            return index >= 0 && index < items.length ? items[index] : fallback;
        }
    }
}
