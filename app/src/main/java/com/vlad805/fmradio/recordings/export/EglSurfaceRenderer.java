package com.vlad805.fmradio.recordings.export;

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Minimal EGL14 + GLES20 helper that uploads {@link Bitmap} frames to a {@code MediaCodec}
 * input {@link Surface} with exact presentation timestamps via {@code eglPresentationTimeANDROID}.
 */
final class EglSurfaceRenderer {
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private static final String VERTEX_SHADER =
            "attribute vec2 aPos;\n" +
            "attribute vec2 aTex;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "  vTex = aTex;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "varying vec2 vTex;\n" +
            "uniform sampler2D uTex;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(uTex, vTex);\n" +
            "}\n";

    // Fullscreen triangle strip with vertical flip so Android Bitmap (top-left origin) renders upright.
    private static final float[] QUAD_COORDS = new float[]{
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f
    };

    private final int mWidth;
    private final int mHeight;
    private final FloatBuffer mVertexBuffer;
    private EGLDisplay mDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface mSurface = EGL14.EGL_NO_SURFACE;
    private int mProgram = 0;
    private int mTextureId = 0;
    private int mPosLoc = -1;
    private int mTexLoc = -1;

    EglSurfaceRenderer(@NonNull final Surface inputSurface, final int width, final int height) throws IOException {
        mWidth = width;
        mHeight = height;

        mVertexBuffer = ByteBuffer.allocateDirect(QUAD_COORDS.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mVertexBuffer.put(QUAD_COORDS).position(0);

        initEgl(inputSurface);
        initGl();
    }

    void renderFrame(@NonNull final Bitmap bitmap, final long presentationTimeUs) {
        GLES20.glViewport(0, 0, mWidth, mHeight);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(mProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureId);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);

        mVertexBuffer.position(0);
        GLES20.glEnableVertexAttribArray(mPosLoc);
        GLES20.glVertexAttribPointer(mPosLoc, 2, GLES20.GL_FLOAT, false, 16, mVertexBuffer);

        mVertexBuffer.position(2);
        GLES20.glEnableVertexAttribArray(mTexLoc);
        GLES20.glVertexAttribPointer(mTexLoc, 2, GLES20.GL_FLOAT, false, 16, mVertexBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(mPosLoc);
        GLES20.glDisableVertexAttribArray(mTexLoc);

        EGLExt.eglPresentationTimeANDROID(mDisplay, mSurface, presentationTimeUs * 1000L);
        EGL14.eglSwapBuffers(mDisplay, mSurface);
    }

    void release() {
        if (mTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[]{mTextureId}, 0);
            mTextureId = 0;
        }

        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }

        if (mDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(mDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (mSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(mDisplay, mSurface);
            }
            if (mContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(mDisplay, mContext);
            }
            EGL14.eglTerminate(mDisplay);
        }

        mDisplay = EGL14.EGL_NO_DISPLAY;
        mContext = EGL14.EGL_NO_CONTEXT;
        mSurface = EGL14.EGL_NO_SURFACE;
    }

    private void initEgl(@NonNull final Surface inputSurface) throws IOException {
        mDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new IOException("Unable to get EGL14 display");
        }

        final int[] version = new int[2];
        if (!EGL14.eglInitialize(mDisplay, version, 0, version, 1)) {
            throw new IOException("Unable to initialize EGL14");
        }

        final int[] attribList = new int[]{
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        final EGLConfig[] configs = new EGLConfig[1];
        final int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(mDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0) || numConfigs[0] == 0) {
            throw new IOException("Unable to choose EGLConfig");
        }

        final int[] contextAttribs = new int[]{
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        mContext = EGL14.eglCreateContext(mDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if (mContext == EGL14.EGL_NO_CONTEXT) {
            throw new IOException("Unable to create EGLContext");
        }

        final int[] surfaceAttribs = new int[]{EGL14.EGL_NONE};
        mSurface = EGL14.eglCreateWindowSurface(mDisplay, configs[0], inputSurface, surfaceAttribs, 0);
        if (mSurface == EGL14.EGL_NO_SURFACE) {
            throw new IOException("Unable to create EGLSurface");
        }

        if (!EGL14.eglMakeCurrent(mDisplay, mSurface, mSurface, mContext)) {
            throw new IOException("eglMakeCurrent failed");
        }
    }

    private void initGl() throws IOException {
        final int vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        final int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);

        mProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mProgram, vs);
        GLES20.glAttachShader(mProgram, fs);
        GLES20.glLinkProgram(mProgram);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);

        final int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(mProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            throw new IOException("GLES20 program link failed");
        }

        mPosLoc = GLES20.glGetAttribLocation(mProgram, "aPos");
        mTexLoc = GLES20.glGetAttribLocation(mProgram, "aTex");

        final int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        mTextureId = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private static int compileShader(final int type, @NonNull final String source) throws IOException {
        final int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);

        final int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            GLES20.glDeleteShader(shader);
            throw new IOException("GLES20 shader compile failed");
        }

        return shader;
    }
}
