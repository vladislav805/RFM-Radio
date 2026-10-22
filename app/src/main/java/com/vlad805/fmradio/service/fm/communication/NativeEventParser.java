package com.vlad805.fmradio.service.fm.communication;

import android.util.Log;

import com.vlad805.fmradio.C;
import com.vlad805.fmradio.service.fm.FMEventCallback;
import com.vlad805.fmradio.service.fm.RadioStatePatch;

import org.json.JSONArray;
import org.json.JSONObject;

public final class NativeEventParser {
    private static final String TAG = "RFM-QCOM";

    private final FMEventCallback callback;

    public NativeEventParser(final FMEventCallback callback) {
        this.callback = callback;
    }

    public void handle(final JSONObject json) {
        final String type = json.optString("type", "");
        switch (type) {
            case "state": {
                final RadioStatePatch patch = parseStatePatch(json);
                if (patch != null && !patch.isEmpty()) {
                    callback.onStatePatch(patch);
                }
                break;
            }

            case "enabled": {
                callback.onEvent(C.Event.ENABLED);
                break;
            }

            case "disabled": {
                // Command completion remains authoritative for the Java lifecycle.
                break;
            }

            case "search_done": {
                final int[] stations = parseIntArray(json.optJSONArray("stations"));
                if (stations != null) {
                    callback.onSearchDone(stations);
                }
                break;
            }

            default: {
                Log.w(TAG, "Unknown native message type: " + type);
                break;
            }
        }
    }

    private RadioStatePatch parseStatePatch(final JSONObject json) {
        final RadioStatePatch patch = new RadioStatePatch();

        if (json.has("frequency")) {
            final int frequency = json.optInt("frequency", -1);
            if (frequency <= 0) {
                Log.w(TAG, "Invalid frequency payload");
                return null;
            }
            patch.setFrequency(frequency);
        }

        if (json.has("stereo")) {
            patch.setStereo(json.optBoolean("stereo", false));
        }

        if (json.has("rmssi")) {
            final int rmssi = json.optInt("rmssi", Integer.MIN_VALUE);
            if (rmssi < -128 || rmssi > 127) {
                Log.w(TAG, "Invalid RMSSI payload");
            } else {
                patch.setRmssi(rmssi);
            }
        }

        final JSONObject rds = json.optJSONObject("rds");
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
                    Log.w(TAG, "Invalid country payload");
                    return null;
                }
                patch.setCountry(country);
            }

            if (rds.has("pty")) {
                final int pty = rds.optInt("pty", -1);
                if (pty < 0) {
                    Log.w(TAG, "Invalid PTY payload");
                    return null;
                }
                patch.setPty(pty);
            }

            if (rds.has("af")) {
                final int[] af = parseIntArray(rds.optJSONArray("af"));
                if (af == null) {
                    Log.w(TAG, "Invalid AF payload");
                    return null;
                }
                patch.setAf(af);
            }
        }

        return patch;
    }

    private static int[] parseIntArray(final JSONArray json) {
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
}
