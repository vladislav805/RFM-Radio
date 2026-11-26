package com.vlad805.fmradio.service.fm.communication;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.vlad805.fmradio.controller.RadioState;
import com.vlad805.fmradio.models.RtPlusFields;
import com.vlad805.fmradio.service.fm.FMEventCallback;
import com.vlad805.fmradio.service.fm.RadioStatePatch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public final class NativeEventParser {
    private static final String TAG = "RFM-QCOM";

    interface JsonObjectView {
        boolean has(String key);

        boolean isNull(String key);

        String optString(String key, String fallback);

        int optInt(String key, int fallback);

        boolean optBoolean(String key, boolean fallback);

        @Nullable
        JsonObjectView optObject(String key);

        @Nullable
        JsonArrayView optArray(String key);

        Iterable<String> keys();

        int length();
    }

    interface JsonArrayView {
        int length();

        int optInt(int index, int fallback);
    }

    private final FMEventCallback callback;

    public NativeEventParser(@NonNull final FMEventCallback callback) {
        this.callback = callback;
    }

    public void handle(@NonNull final JSONObject json) {
        handle(wrapObject(json));
    }

    void handle(@NonNull final JsonObjectView json) {
        final String type = json.optString("type", "");
        switch (type) {
            case "state": {
                final RadioStatePatch patch = parseStatePatch(json);
                if (patch != null && !patch.isEmpty()) {
                    callback.onStatePatch(patch);
                }
                break;
            }

            case "enabled":
            case "disabled":
            case "seek_done": {
                // Command completion is authoritative for Java enable/disable lifecycle,
                // and seek frequencies arrive via "state" patches.
                break;
            }

            case "search_progress": {
                final int count = json.optInt("count", -1);
                if (count >= 0) {
                    callback.onSearchProgress(count);
                }
                break;
            }

            case "search_done": {
                final int[] stations = parseIntArray(json.optArray("stations"));
                if (stations != null) {
                    callback.onSearchDone(stations);
                }
                break;
            }

            case "search_failed": {
                callback.onSearchFailed();
                break;
            }

            default: {
                logWarn("Unknown native message type: " + type);
                break;
            }
        }
    }

    @Nullable
    private RadioStatePatch parseStatePatch(@NonNull final JsonObjectView json) {
        final RadioStatePatch patch = new RadioStatePatch();

        if (json.has("frequency")) {
            final int frequency = json.optInt("frequency", -1);
            if (frequency <= 0) {
                logWarn("Invalid frequency payload");
                return null;
            }
            patch.setFrequency(frequency);
        }

        if (json.has("stereo")) {
            patch.setStereo(json.optBoolean("stereo", false));
        }

        if (json.has("rmssi")) {
            if (json.isNull("rmssi")) {
                patch.setRmssi(RadioState.RMSSI_UNKNOWN);
            } else {
                final int rmssi = json.optInt("rmssi", Integer.MIN_VALUE);
                if (rmssi < -128 || rmssi > 127) {
                    logWarn("Invalid RMSSI payload");
                } else {
                    patch.setRmssi(rmssi);
                }
            }
        }

        if (json.has("sinr")) {
            if (json.isNull("sinr")) {
                patch.setSinr(RadioState.SINR_UNKNOWN);
            } else {
                final int sinr = json.optInt("sinr", Integer.MIN_VALUE);
                if (sinr < -128 || sinr > 127) {
                    logWarn("Invalid SINR payload");
                } else {
                    patch.setSinr(sinr);
                }
            }
        }

        final JsonObjectView rds = json.optObject("rds");
        if (rds != null) {
            if (rds.has("ps")) {
                patch.setPs(sanitizeRdsText(rds.optString("ps", "")));
            }

            if (rds.has("rt")) {
                patch.setRt(sanitizeRdsText(rds.optString("rt", "")));
            }

            if (rds.has("pi")) {
                patch.setPi(rds.optString("pi", ""));
            }

            if (rds.has("country")) {
                final String country = rds.optString("country", "");
                if (!country.isEmpty() && !country.matches("[A-Z]{2}")) {
                    logWarn("Invalid country payload");
                    return null;
                }
                patch.setCountry(country);
            }

            if (rds.has("pty")) {
                final int pty = rds.optInt("pty", -1);
                if (pty < 0) {
                    logWarn("Invalid PTY payload");
                    return null;
                }
                patch.setPty(pty);
            }

            if (rds.has("af")) {
                final int[] af = parseIntArray(rds.optArray("af"));
                if (af == null) {
                    logWarn("Invalid AF payload");
                    return null;
                }
                patch.setAf(af);
            }

            final JsonObjectView rtPlus = rds.optObject("rt_plus");
            if (rtPlus != null) {
                final Map<String, String> fields = new HashMap<>();

                for (final String key : rtPlus.keys()) {
                    if (RtPlusFields.names().contains(key)) {
                        fields.put(key, rtPlus.isNull(key) ? "" : sanitizeRdsText(rtPlus.optString(key, "")));
                    }
                }

                if (rtPlus.length() == 0 || !fields.isEmpty()) {
                    patch.setRtPlusFields(fields);
                }
            }
        }

        return patch;
    }

    @Nullable
    private static int[] parseIntArray(@Nullable final JsonArrayView json) {
        if (json == null) {
            return null;
        }

        final int[] result = new int[json.length()];
        for (int i = 0; i < json.length(); ++i) {
            result[i] = json.optInt(i, -1);
            if (result[i] < 0) {
                return null;
            }
        }

        return result;
    }

    private static String sanitizeRdsText(final String value) {
        return value.trim().replaceAll("\n", " ");
    }

    private static void logWarn(final String message) {
        try {
            Log.w(TAG, message);
        } catch (final RuntimeException ignored) {
            // Host JVM unit tests run with unmocked android.util.Log.
        }
    }

    private static JsonObjectView wrapObject(@NonNull final JSONObject object) {
        return new JsonObjectView() {
            @Override
            public boolean has(final String key) {
                return object.has(key);
            }

            @Override
            public boolean isNull(final String key) {
                return object.isNull(key);
            }

            @Override
            public String optString(final String key, final String fallback) {
                return object.optString(key, fallback);
            }

            @Override
            public int optInt(final String key, final int fallback) {
                return object.optInt(key, fallback);
            }

            @Override
            public boolean optBoolean(final String key, final boolean fallback) {
                return object.optBoolean(key, fallback);
            }

            @Nullable
            @Override
            public JsonObjectView optObject(final String key) {
                final JSONObject nested = object.optJSONObject(key);
                return nested != null ? wrapObject(nested) : null;
            }

            @Nullable
            @Override
            public JsonArrayView optArray(final String key) {
                final JSONArray array = object.optJSONArray(key);
                return array != null ? wrapArray(array) : null;
            }

            @Override
            public Iterable<String> keys() {
                return object::keys;
            }

            @Override
            public int length() {
                return object.length();
            }
        };
    }

    private static JsonArrayView wrapArray(@NonNull final JSONArray array) {
        return new JsonArrayView() {
            @Override
            public int length() {
                return array.length();
            }

            @Override
            public int optInt(final int index, final int fallback) {
                return array.optInt(index, fallback);
            }
        };
    }
}
