package com.vlad805.fmradio.helper;

import android.annotation.SuppressLint;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * vlad805 (c) 2021
 */
public class Audio {
    private static final int FOR_MEDIA = 1;
    private static final int DEFAULT_SAMSUNG_FOR_FM = 8;
    public static final int FORCE_NONE = 0;
    public static final int FORCE_SPEAKER = 1;

    private static Method setForceUseMethod = null;
    private static Method getForceUseMethod = null;

    static {
        try {
            @SuppressLint("PrivateApi")
            final Class<?> audioSystemClass = Class.forName("android.media.AudioSystem");

            setForceUseMethod = audioSystemClass.getMethod("setForceUse", int.class, int.class);
            getForceUseMethod = audioSystemClass.getMethod("getForceUse", int.class);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            e.printStackTrace();
        }
    }

    private static void setForceUse(final int mode) {
        if (setForceUseMethod != null) {
            try {
                setForceUseMethod.invoke(null, FOR_MEDIA, mode);
            } catch (IllegalAccessException | InvocationTargetException e) {
                e.printStackTrace();
            }
        }
    }

    private static int getForceUse() {
        if (getForceUseMethod != null) {
            try {
                return (int) getForceUseMethod.invoke(int.class, FOR_MEDIA);
            } catch (IllegalAccessException | InvocationTargetException e) {
                e.printStackTrace();
            }
        }
        return -1;
    }

    public static boolean isForceSpeakerNow() {
        return getForceUse() == FORCE_SPEAKER;
    }

    public static void toggleThroughSpeaker(final boolean enableSpeaker) {
        if (enableSpeaker) {
            setForceUse(FORCE_SPEAKER);
        } else {
            setForceUse(FORCE_NONE);
        }
    }

    /**
     * Resolves Samsung's AudioSystem.FOR_FM constant (usage=8 in OneUI AudioPolicyManager).
     */
    public static int resolveSamsungFmForceUsage() {
        try {
            @SuppressLint("PrivateApi")
            final Class<?> audioSystemClass = Class.forName("android.media.AudioSystem");
            final Field field = audioSystemClass.getField("FOR_FM");
            return field.getInt(null);
        } catch (ReflectiveOperationException e) {
            return DEFAULT_SAMSUNG_FOR_FM;
        }
    }

    public static int invokeSetForceUseDirect(final int usage, final int config) {
        if (setForceUseMethod == null) {
            return -1;
        }
        try {
            final Object result = setForceUseMethod.invoke(null, usage, config);
            return result instanceof Integer ? (Integer) result : 0;
        } catch (IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
            return -1;
        }
    }

    @SuppressLint("PrivateApi")
    public static int setDeviceConnectionState(final int device, final int state) {
        if (device == 0) {
            return -1;
        }
        try {
            final Class<?> audioSystemClass = Class.forName("android.media.AudioSystem");
            try {
                final Method m3 = audioSystemClass.getMethod("setDeviceConnectionState", int.class, int.class, String.class);
                final Object res = m3.invoke(null, device, state, "");
                return res instanceof Integer ? (Integer) res : 0;
            } catch (NoSuchMethodException ignored) {
                final Method m4 = audioSystemClass.getMethod("setDeviceConnectionState", int.class, int.class, String.class, String.class);
                final Object res = m4.invoke(null, device, state, "", "");
                return res instanceof Integer ? (Integer) res : 0;
            }
        } catch (ReflectiveOperationException e) {
            e.printStackTrace();
            return -1;
        }
    }
}
