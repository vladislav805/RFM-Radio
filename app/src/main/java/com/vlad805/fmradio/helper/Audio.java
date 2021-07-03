package com.vlad805.fmradio.helper;

import android.annotation.SuppressLint;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * vlad805 (c) 2021
 */
public class Audio {
    private static final int FOR_MEDIA = 1;
    private static final int FORCE_NONE = 0;
    private static final int FORCE_SPEAKER = 1;

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
}
