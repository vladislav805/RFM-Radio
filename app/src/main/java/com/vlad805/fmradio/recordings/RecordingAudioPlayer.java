package com.vlad805.fmradio.recordings;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unified local recording player supporting WAV, MP3, and Ogg Opus across API 23+.
 * Uses Android {@link MediaPlayer} for WAV, MP3, and API 29+ Opus, and falls back to
 * {@link OggOpusDecoder} + {@link AudioTrack} for Ogg Opus on API 23–28.
 */
public final class RecordingAudioPlayer {
    public interface Listener {
        void onPrepared(long durationMs);

        void onPlaybackStateChanged(boolean isPlaying);

        void onCompleted();

        void onError(@NonNull String message);
    }

    private interface Backend {
        void play();

        void pause();

        void seekTo(long positionMs);

        long getCurrentPositionMs();

        long getDurationMs();

        boolean isPlaying();

        void release();
    }

    private final Context mContext;
    private final AudioManager mAudioManager;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    @Nullable
    private Listener mListener;
    @Nullable
    private Backend mBackend;
    @Nullable
    private AudioFocusRequest mFocusRequest;
    private boolean mHasAudioFocus;

    private final AudioManager.OnAudioFocusChangeListener mFocusChangeListener = focusChange -> {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pause();
        }
    };

    public RecordingAudioPlayer(@NonNull final Context context) {
        mContext = context.getApplicationContext();
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
    }

    public void setListener(@Nullable final Listener listener) {
        mListener = listener;
    }

    public void prepare(@NonNull final RecordingItem item, final boolean autoPlay) {
        releaseBackend();

        final boolean useNativeOpus = (
                "opus".equalsIgnoreCase(item.getExtension()) &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        );

        try {
            if (useNativeOpus) {
                mBackend = new OpusAudioTrackBackend(mContext, item, autoPlay);
            } else {
                mBackend = new MediaPlayerBackend(mContext, item, autoPlay);
            }
        } catch (final IOException | RuntimeException error) {
            releaseBackend();
            notifyError(error.getMessage() != null ? error.getMessage() : "Playback error");
        }
    }

    public void play() {
        if (mBackend == null) {
            return;
        }

        requestAudioFocus();
        mBackend.play();
        notifyPlaybackState(true);
    }

    public void pause() {
        if (mBackend == null) {
            return;
        }

        mBackend.pause();
        abandonAudioFocus();
        notifyPlaybackState(false);
    }

    public void seekTo(final long positionMs) {
        if (mBackend != null) {
            mBackend.seekTo(Math.max(0L, positionMs));
        }
    }

    public long getCurrentPositionMs() {
        return mBackend != null ? mBackend.getCurrentPositionMs() : 0L;
    }

    public long getDurationMs() {
        return mBackend != null ? mBackend.getDurationMs() : 0L;
    }

    public boolean isPlaying() {
        return mBackend != null && mBackend.isPlaying();
    }

    public void release() {
        releaseBackend();
        abandonAudioFocus();
    }

    private void releaseBackend() {
        if (mBackend != null) {
            mBackend.release();
            mBackend = null;
        }
    }

    @SuppressWarnings("deprecation")
    private void requestAudioFocus() {
        if (mAudioManager == null || mHasAudioFocus) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            mFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(mFocusChangeListener)
                    .build();
            mHasAudioFocus = mAudioManager.requestAudioFocus(mFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            mHasAudioFocus = mAudioManager.requestAudioFocus(
                    mFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    @SuppressWarnings("deprecation")
    private void abandonAudioFocus() {
        if (mAudioManager == null || !mHasAudioFocus) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mFocusRequest != null) {
            mAudioManager.abandonAudioFocusRequest(mFocusRequest);
            mFocusRequest = null;
        } else {
            mAudioManager.abandonAudioFocus(mFocusChangeListener);
        }

        mHasAudioFocus = false;
    }

    private void notifyPrepared(final long durationMs) {
        mMainHandler.post(() -> {
            if (mListener != null) {
                mListener.onPrepared(durationMs);
            }
        });
    }

    private void notifyPlaybackState(final boolean playing) {
        mMainHandler.post(() -> {
            if (mListener != null) {
                mListener.onPlaybackStateChanged(playing);
            }
        });
    }

    private void notifyCompleted() {
        mMainHandler.post(() -> {
            abandonAudioFocus();
            if (mListener != null) {
                mListener.onPlaybackStateChanged(false);
                mListener.onCompleted();
            }
        });
    }

    private void notifyError(@NonNull final String message) {
        mMainHandler.post(() -> {
            abandonAudioFocus();
            if (mListener != null) {
                mListener.onError(message);
            }
        });
    }

    // --- MediaPlayer backend ---

    private final class MediaPlayerBackend implements Backend {
        private final MediaPlayer mPlayer = new MediaPlayer();
        private volatile boolean mPrepared;
        private volatile long mDurationMs;

        MediaPlayerBackend(
                @NonNull final Context context,
                @NonNull final RecordingItem item,
                final boolean autoPlay
        ) throws IOException {
            mPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
            );

            if (item.getContentUri() != null) {
                mPlayer.setDataSource(context, item.getContentUri());
            } else if (item.getFilePath() != null) {
                mPlayer.setDataSource(item.getFilePath());
            } else {
                throw new IOException("Missing recording path");
            }

            mPlayer.setOnPreparedListener(mp -> {
                mPrepared = true;
                mDurationMs = Math.max(0L, mp.getDuration());
                notifyPrepared(mDurationMs);

                if (autoPlay) {
                    requestAudioFocus();
                    mp.start();
                    notifyPlaybackState(true);
                }
            });

            mPlayer.setOnCompletionListener(mp -> notifyCompleted());

            mPlayer.setOnErrorListener((mp, what, extra) -> {
                notifyError("MediaPlayer error (" + what + "/" + extra + ")");
                return true;
            });

            mPlayer.prepareAsync();
        }

        @Override
        public void play() {
            if (mPrepared && !mPlayer.isPlaying()) {
                mPlayer.start();
            }
        }

        @Override
        public void pause() {
            if (mPrepared && mPlayer.isPlaying()) {
                mPlayer.pause();
            }
        }

        @Override
        public void seekTo(final long positionMs) {
            if (mPrepared) {
                mPlayer.seekTo((int) Math.min(Integer.MAX_VALUE, positionMs));
            }
        }

        @Override
        public long getCurrentPositionMs() {
            return mPrepared ? Math.max(0L, mPlayer.getCurrentPosition()) : 0L;
        }

        @Override
        public long getDurationMs() {
            return mDurationMs;
        }

        @Override
        public boolean isPlaying() {
            return mPrepared && mPlayer.isPlaying();
        }

        @Override
        public void release() {
            mPrepared = false;
            try {
                mPlayer.reset();
            } catch (final IllegalStateException ignored) {
            }
            mPlayer.release();
        }
    }

    // --- Native OggOpusDecoder + AudioTrack backend (API 23–28) ---

    private final class OpusAudioTrackBackend implements Backend {
        private final OggOpusDecoder mDecoder;
        private final AudioTrack mTrack;
        private final long mDurationMs;
        private final Object mLock = new Object();
        private final AtomicBoolean mPlaying = new AtomicBoolean(false);
        private final AtomicBoolean mReleased = new AtomicBoolean(false);
        private final AtomicLong mPositionMs = new AtomicLong(0L);
        private final AtomicLong mPendingSeekMs = new AtomicLong(-1L);
        private final Thread mThread;

        OpusAudioTrackBackend(
                @NonNull final Context context,
                @NonNull final RecordingItem item,
                final boolean autoPlay
        ) throws IOException {
            if (item.getContentUri() != null) {
                try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(item.getContentUri(), "r")) {
                    if (pfd == null) {
                        throw new IOException("Cannot open Opus URI");
                    }
                    mDecoder = OggOpusDecoder.open(pfd);
                }
            } else if (item.getFilePath() != null) {
                mDecoder = OggOpusDecoder.open(new File(item.getFilePath()));
            } else {
                throw new IOException("Missing Opus path");
            }

            mDurationMs = mDecoder.getDurationMillis();

            final int minBuf = AudioTrack.getMinBufferSize(
                    OggOpusDecoder.SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            mTrack = new AudioTrack.Builder()
                    .setAudioAttributes(
                            new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build()
                    )
                    .setAudioFormat(
                            new AudioFormat.Builder()
                                    .setSampleRate(OggOpusDecoder.SAMPLE_RATE)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(Math.max(minBuf * 2, 16384))
                    .build();

            mThread = new Thread(this::playbackLoop, "FmOpusPlayback");
            mThread.start();

            notifyPrepared(mDurationMs);

            if (autoPlay) {
                requestAudioFocus();
                play();
                notifyPlaybackState(true);
            }
        }

        private void playbackLoop() {
            final short[] pcm = new short[4096];

            while (!mReleased.get()) {
                final long seekTarget = mPendingSeekMs.getAndSet(-1L);
                if (seekTarget >= 0L) {
                    mDecoder.seekMillis(seekTarget);
                    mPositionMs.set(seekTarget);
                    mTrack.flush();
                }

                if (!mPlaying.get()) {
                    synchronized (mLock) {
                        if (!mPlaying.get() && !mReleased.get() && mPendingSeekMs.get() < 0L) {
                            try {
                                mLock.wait(100L);
                            } catch (final InterruptedException ignored) {
                                break;
                            }
                        }
                    }
                    continue;
                }

                final int read = mDecoder.decode(pcm);
                if (read <= 0) {
                    mPlaying.set(false);
                    mTrack.pause();
                    mPositionMs.set(mDurationMs);
                    notifyCompleted();
                    continue;
                }

                mTrack.write(pcm, 0, read);
                final long deltaMs = (read * 1000L) / (OggOpusDecoder.SAMPLE_RATE * (long) OggOpusDecoder.CHANNELS);
                mPositionMs.addAndGet(deltaMs);
            }
        }

        @Override
        public void play() {
            if (mReleased.get()) {
                return;
            }

            if (mPositionMs.get() >= mDurationMs && mDurationMs > 0L) {
                seekTo(0L);
            }

            mPlaying.set(true);
            mTrack.play();

            synchronized (mLock) {
                mLock.notifyAll();
            }
        }

        @Override
        public void pause() {
            mPlaying.set(false);
            try {
                mTrack.pause();
            } catch (final IllegalStateException ignored) {
            }
        }

        @Override
        public void seekTo(final long positionMs) {
            final long clamped = Math.max(0L, Math.min(positionMs, mDurationMs));
            mPositionMs.set(clamped);
            mPendingSeekMs.set(clamped);

            synchronized (mLock) {
                mLock.notifyAll();
            }
        }

        @Override
        public long getCurrentPositionMs() {
            return mPositionMs.get();
        }

        @Override
        public long getDurationMs() {
            return mDurationMs;
        }

        @Override
        public boolean isPlaying() {
            return mPlaying.get();
        }

        @Override
        public void release() {
            mReleased.set(true);
            mPlaying.set(false);

            synchronized (mLock) {
                mLock.notifyAll();
            }

            try {
                mTrack.pause();
                mTrack.flush();
            } catch (final IllegalStateException ignored) {
            }

            try {
                mThread.join(1000L);
            } catch (final InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            mTrack.release();
            mDecoder.close();
        }
    }
}
