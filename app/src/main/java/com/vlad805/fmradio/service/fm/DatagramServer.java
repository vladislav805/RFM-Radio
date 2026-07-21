package com.vlad805.fmradio.service.fm;

import android.util.Log;

import com.vlad805.fmradio.C;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

/**
 * vlad805 (c) 2019
 */
public class DatagramServer extends Thread {
    private final DatagramSocket mDatagramSocketServer;
    private FMEventCallback mCallback;

    private static final int BUFFER_SIZE = 512;
    private static final String TAG = "FMELS";

    private boolean mEnabled = false;

    public DatagramServer(final int port) throws IOException {
        mDatagramSocketServer = new DatagramSocket(port);
    }

    public void setCallback(final FMEventCallback callback) {
        mCallback = callback;
    }

    @Override
    public void run() {
        mEnabled = true;

        byte[] buffer = new byte[BUFFER_SIZE];
        while (mEnabled) {
            DatagramPacket dp = new DatagramPacket(buffer, 0, BUFFER_SIZE);

            try {
                mDatagramSocketServer.receive(dp);

                final String message = new String(dp.getData(), 0, dp.getLength(), StandardCharsets.UTF_8);
                handle(message);

                dp.setData("ok".getBytes(StandardCharsets.UTF_8));
                mDatagramSocketServer.send(dp);
            } catch (SocketException e) {
                if (mEnabled) {
                    e.printStackTrace();
                }
                mEnabled = false;
            } catch (IOException e) {
                if (mEnabled) {
                    e.printStackTrace();
                }
            } catch (StopServer e) {
                mEnabled = false;
            }
        }
    }

    private void handle(final String message) throws StopServer {
        final JSONObject json;
        try {
            json = new JSONObject(message);
        } catch (final JSONException e) {
            Log.w(TAG, "invalid native JSON", e);
            return;
        }

        final String type = json.optString("type", "");
        switch (type) {
            case "state": {
                final RadioStatePatch patch = parseStatePatch(json);
                if (patch != null && !patch.isEmpty() && mCallback != null) {
                    mCallback.onStatePatch(patch);
                }
                break;
            }

            case "enabled": {
                if (mCallback != null) {
                    mCallback.onEvent(C.Event.ENABLED);
                }
                break;
            }

            case "disabled": {
                throw new StopServer();
            }

            case "search_done": {
                final int[] stations = parseIntArray(json.optJSONArray("stations"));
                if (stations != null && mCallback != null) {
                    mCallback.onSearchDone(stations);
                }
                break;
            }

            default: {
                Log.w(TAG, "unknown native message type = " + type);
                break;
            }
        }
    }

    private RadioStatePatch parseStatePatch(final JSONObject json) {
        final RadioStatePatch patch = new RadioStatePatch();

        if (json.has("frequency")) {
            final int frequency = json.optInt("frequency", -1);
            if (frequency <= 0) {
                Log.w(TAG, "invalid frequency payload");
                return null;
            }
            patch.setFrequency(frequency);
        }

        if (json.has("stereo")) {
            patch.setStereo(json.optBoolean("stereo", false));
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
                    Log.w(TAG, "invalid country payload");
                    return null;
                }
                patch.setCountry(country);
            }

            if (rds.has("pty")) {
                final int pty = rds.optInt("pty", -1);
                if (pty < 0) {
                    Log.w(TAG, "invalid PTY payload");
                    return null;
                }
                patch.setPty(pty);
            }

            if (rds.has("af")) {
                final int[] af = parseIntArray(rds.optJSONArray("af"));
                if (af == null) {
                    Log.w(TAG, "invalid AF payload");
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

    private static class StopServer extends Throwable { }

    public void closeServer() {
        mEnabled = false;
        if (!mDatagramSocketServer.isClosed()) {
            mDatagramSocketServer.close();
        }
    }
}
