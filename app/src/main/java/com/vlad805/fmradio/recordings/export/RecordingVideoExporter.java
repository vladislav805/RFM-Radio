package com.vlad805.fmradio.recordings.export;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.google.android.material.color.MaterialColors;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.recordings.OggOpusDecoder;
import com.vlad805.fmradio.recordings.RecordingItem;
import com.vlad805.fmradio.recordings.RecordingTunerBinder;
import com.vlad805.fmradio.recordings.RecordingsRepository;
import com.vlad805.fmradio.service.recording.RfmMetadata;
import com.vlad805.fmradio.service.recording.RfmMetadataEvent;
import com.vlad805.fmradio.view.WaveformView;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exports a recording with embedded {@link RfmMetadata} as an MP4 video (H.264 + AAC)
 * showing live {@code RadioUI} (RDS, signal strength, stereo badge, waveform playhead).
 */
public final class RecordingVideoExporter {
    public interface ProgressListener {
        /**
         * Reports parallel encoding progress ({@code saving == false}: {@code audioPercent} and
         * {@code videoPercent} in 0..100) or final mux/save stage ({@code saving == true}).
         */
        void onProgress(int audioPercent, int videoPercent, boolean saving);
    }

    public static final class ExportResult {
        @NonNull
        public final String displayName;
        @Nullable
        public final Uri contentUri;
        @Nullable
        public final String filePath;

        ExportResult(
                @NonNull final String displayName,
                @Nullable final Uri contentUri,
                @Nullable final String filePath
        ) {
            this.displayName = displayName;
            this.contentUri = contentUri;
            this.filePath = filePath;
        }
    }

    private static final int VIDEO_WIDTH = 960;
    private static final int VIDEO_HEIGHT = 720;
    private static final int VIDEO_BITRATE = 2_000_000;
    private static final int VIDEO_NOMINAL_FPS = 15;
    private static final long FRAME_STEP_MS = 250L; // 4 rendered frames/sec
    private static final int AAC_BITRATE = 192_000;
    private static final long CODEC_TIMEOUT_US = 1_000L;
    private static final String MOVIES_SUBDIR = "RFM-Recordings";

    private RecordingVideoExporter() {}

    @NonNull
    public static ExportResult export(
            @NonNull final Context themedActivityContext,
            @NonNull final RecordingItem item,
            @NonNull final RfmMetadata metadata,
            final long durationHintMs,
            @Nullable final ProgressListener progressListener,
            @Nullable final AtomicBoolean cancelled
    ) throws IOException {
        final File cacheDir = new File(themedActivityContext.getCacheDir(), "video_export");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IOException("Cannot create video export cache dir");
        }

        final File tempAudioM4a = File.createTempFile("rfm_audio_", ".m4a", cacheDir);
        final File tempVideoOnlyMp4 = File.createTempFile("rfm_vonly_", ".mp4", cacheDir);
        final File tempFinalMp4 = File.createTempFile("rfm_video_", ".mp4", cacheDir);
        final ProgressTracker tracker = new ProgressTracker(progressListener);
        final AtomicBoolean abortFlag = new AtomicBoolean(false);
        final CancelToken cancelToken = new CancelToken(cancelled, abortFlag);

        try {
            cancelToken.check();
            tracker.emit();

            if (durationHintMs > 0L) {
                // Run software AAC audio encoding and hardware H.264 video rendering in parallel.
                final ExecutorService audioExec = Executors.newSingleThreadExecutor();
                final Future<Long> audioFuture = audioExec.submit(() -> encodeAudioToM4a(
                        themedActivityContext,
                        item,
                        tempAudioM4a,
                        tracker,
                        cancelToken
                ));

                try {
                    encodeVideoOnly(
                            themedActivityContext,
                            item,
                            metadata,
                            durationHintMs,
                            tempVideoOnlyMp4,
                            tracker,
                            cancelToken
                    );
                    tracker.setVideoPercent(100);

                    try {
                        audioFuture.get();
                    } catch (final ExecutionException e) {
                        final Throwable cause = e.getCause();
                        if (cause instanceof CancellationException) {
                            throw (CancellationException) cause;
                        }
                        if (cause instanceof IOException) {
                            throw (IOException) cause;
                        }
                        throw new IOException("Audio encoding failed", cause);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException("Export interrupted");
                    }
                    tracker.setAudioPercent(100);
                } catch (final Throwable t) {
                    abortFlag.set(true);
                    audioFuture.cancel(false);
                    try {
                        audioFuture.get();
                    } catch (final Throwable ignored) {
                    }
                    if (t instanceof IOException) {
                        throw (IOException) t;
                    }
                    if (t instanceof RuntimeException) {
                        throw (RuntimeException) t;
                    }
                    throw new IOException("Export failed", t);
                } finally {
                    audioExec.shutdownNow();
                }
            } else {
                // Fallback when duration hint is unavailable: encode audio first to discover duration.
                final long audioDurationMs = encodeAudioToM4a(
                        themedActivityContext,
                        item,
                        tempAudioM4a,
                        tracker,
                        cancelToken
                );
                if (audioDurationMs <= 0L) {
                    throw new IOException("Recording duration is zero");
                }
                tracker.setAudioPercent(100);

                encodeVideoOnly(
                        themedActivityContext,
                        item,
                        metadata,
                        audioDurationMs,
                        tempVideoOnlyMp4,
                        tracker,
                        cancelToken
                );
                tracker.setVideoPercent(100);
            }

            cancelToken.check();
            tracker.setSaving();

            // Mux the finished H.264 video and AAC audio tracks together, then publish.
            muxTracks(tempVideoOnlyMp4, tempAudioM4a, tempFinalMp4, cancelToken);
            cancelToken.check();

            final String outName = buildOutputName(item.getDisplayName());
            return publishMp4(themedActivityContext, tempFinalMp4, outName);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tempAudioM4a.delete();
            //noinspection ResultOfMethodCallIgnored
            tempVideoOnlyMp4.delete();
            //noinspection ResultOfMethodCallIgnored
            tempFinalMp4.delete();
        }
    }

    @NonNull
    public static Intent createShareIntent(
            @NonNull final Context context,
            @NonNull final ExportResult result,
            @NonNull final CharSequence chooserTitle
    ) {
        final Uri uri;
        if (result.contentUri != null) {
            uri = result.contentUri;
        } else if (result.filePath != null) {
            uri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    new File(result.filePath)
            );
        } else {
            throw new IllegalArgumentException("Missing exported video location");
        }

        final Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("video/mp4");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return Intent.createChooser(send, chooserTitle);
    }

    // --- Pass 1: Audio decode → AAC .m4a ---

    private static long encodeAudioToM4a(
            @NonNull final Context context,
            @NonNull final RecordingItem item,
            @NonNull final File outM4a,
            @NonNull final ProgressTracker tracker,
            @NonNull final CancelToken cancelToken
    ) throws IOException {
        final String ext = item.getExtension().toLowerCase(Locale.US);

        if ("wav".equals(ext)) {
            return encodeWavToM4a(context, item, outM4a, tracker, cancelToken);
        }

        if ("opus".equals(ext)) {
            return encodeOpusToM4a(context, item, outM4a, tracker, cancelToken);
        }

        return encodeViaMediaExtractorToM4a(context, item, outM4a, tracker, cancelToken);
    }

    private static long encodeWavToM4a(
            @NonNull final Context context,
            @NonNull final RecordingItem item,
            @NonNull final File outM4a,
            @NonNull final ProgressTracker tracker,
            @NonNull final CancelToken cancelToken
    ) throws IOException {
        try (InputStream rawIn = RecordingsRepository.openInputStream(context, item)) {
            if (rawIn == null) {
                throw new IOException("Cannot open WAV recording");
            }

            final BufferedInputStream in = new BufferedInputStream(rawIn, 32768);
            final byte[] riffHeader = new byte[12];
            readFully(in, riffHeader, 12);

            int sampleRate = 48000;
            int channels = 2;
            long dataSize = 0L;

            final byte[] chunkHeader = new byte[8];
            while (readChunkHeader(in, chunkHeader)) {
                final String id = new String(chunkHeader, 0, 4);
                final long size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;

                if ("fmt ".equals(id) && size >= 16L) {
                    final byte[] fmt = new byte[(int) Math.min(size, 64L)];
                    readFully(in, fmt, fmt.length);
                    final ByteBuffer bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN);
                    channels = bb.getShort(2) & 0xFFFF;
                    sampleRate = bb.getInt(4);
                    if (size > fmt.length) {
                        skipFully(in, size - fmt.length);
                    }
                    if ((size & 1L) != 0L) {
                        skipFully(in, 1L);
                    }
                } else if ("data".equals(id)) {
                    dataSize = size;
                    break;
                } else {
                    skipFully(in, size + (size & 1L));
                }
            }

            if (dataSize <= 0L) {
                throw new IOException("WAV data chunk not found");
            }

            final AacM4aSink sink = new AacM4aSink(outM4a, sampleRate, channels);
            try {
                final byte[] buf = new byte[8192];
                long remaining = dataSize;

                while (remaining > 0L) {
                    cancelToken.check();
                    final int toRead = (int) Math.min(buf.length, remaining);
                    final int read = in.read(buf, 0, toRead);
                    if (read <= 0) {
                        break;
                    }

                    sink.writePcm(buf, read);
                    remaining -= read;

                    if (dataSize > 0L) {
                        final int pct = (int) (((dataSize - remaining) * 100L) / dataSize);
                        tracker.setAudioPercent(pct);
                    }
                }

                return sink.finish();
            } finally {
                sink.release();
            }
        }
    }

    private static long encodeOpusToM4a(
            @NonNull final Context context,
            @NonNull final RecordingItem item,
            @NonNull final File outM4a,
            @NonNull final ProgressTracker tracker,
            @NonNull final CancelToken cancelToken
    ) throws IOException {
        final OggOpusDecoder decoder;
        if (item.getContentUri() != null) {
            try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(item.getContentUri(), "r")) {
                if (pfd == null) {
                    throw new IOException("Cannot open Opus URI");
                }
                decoder = OggOpusDecoder.open(pfd);
            }
        } else if (item.getFilePath() != null) {
            decoder = OggOpusDecoder.open(new File(item.getFilePath()));
        } else {
            throw new IOException("Missing Opus path");
        }

        final AacM4aSink sink = new AacM4aSink(outM4a, OggOpusDecoder.SAMPLE_RATE, OggOpusDecoder.CHANNELS);
        try {
            final long estDurationMs = Math.max(1L, decoder.getDurationMillis());
            final short[] pcmShort = new short[4096];
            final byte[] pcmBytes = new byte[pcmShort.length * 2];
            long totalFrames = 0L;

            while (true) {
                cancelToken.check();
                final int samples = decoder.decode(pcmShort);
                if (samples <= 0) {
                    break;
                }

                ByteBuffer.wrap(pcmBytes, 0, samples * 2)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                        .put(pcmShort, 0, samples);
                sink.writePcm(pcmBytes, samples * 2);

                totalFrames += samples / OggOpusDecoder.CHANNELS;
                final long doneMs = (totalFrames * 1000L) / OggOpusDecoder.SAMPLE_RATE;
                final int pct = (int) Math.min(100L, (doneMs * 100L) / estDurationMs);
                tracker.setAudioPercent(pct);
            }

            return sink.finish();
        } finally {
            sink.release();
            decoder.close();
        }
    }

    private static long encodeViaMediaExtractorToM4a(
            @NonNull final Context context,
            @NonNull final RecordingItem item,
            @NonNull final File outM4a,
            @NonNull final ProgressTracker tracker,
            @NonNull final CancelToken cancelToken
    ) throws IOException {
        final MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        AacM4aSink sink = null;

        try {
            if (item.getContentUri() != null) {
                extractor.setDataSource(context, item.getContentUri(), null);
            } else if (item.getFilePath() != null) {
                extractor.setDataSource(item.getFilePath());
            } else {
                throw new IOException("Missing audio source");
            }

            int audioTrack = -1;
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                final MediaFormat fmt = extractor.getTrackFormat(i);
                final String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    inputFormat = fmt;
                    break;
                }
            }

            if (audioTrack < 0 || inputFormat == null) {
                throw new IOException("No audio track found");
            }

            extractor.selectTrack(audioTrack);
            final String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            final int sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            final int channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            final long durationUs = inputFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? inputFormat.getLong(MediaFormat.KEY_DURATION)
                    : Math.max(1_000_000L, item.getDurationMillis() * 1000L);

            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            sink = new AacM4aSink(outM4a, sampleRate, channels);
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;

            while (!outputDone) {
                cancelToken.check();
                if (!inputDone) {
                    final int inIdx = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (inIdx >= 0) {
                        final ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
                        if (inBuf != null) {
                            final int sampleSize = extractor.readSampleData(inBuf, 0);
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                final long pts = extractor.getSampleTime();
                                decoder.queueInputBuffer(inIdx, 0, sampleSize, pts, 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                final int outIdx = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        final ByteBuffer outBuf = decoder.getOutputBuffer(outIdx);
                        if (outBuf != null) {
                            outBuf.position(info.offset);
                            outBuf.limit(info.offset + info.size);
                            final byte[] pcm = new byte[info.size];
                            outBuf.get(pcm);
                            sink.writePcm(pcm, pcm.length);
                        }

                        if (durationUs > 0L) {
                            final int pct = (int) Math.min(100L, (info.presentationTimeUs * 100L) / durationUs);
                            tracker.setAudioPercent(pct);
                        }
                    }

                    decoder.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }

            return sink.finish();
        } finally {
            if (sink != null) {
                sink.release();
            }
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (final RuntimeException ignored) {
                }
                decoder.release();
            }
            extractor.release();
        }
    }

    private static final class AacM4aSink {
        private final MediaCodec mEncoder;
        private final MediaMuxer mMuxer;
        private final int mBytesPerFrame;
        private final int mSampleRate;
        private final MediaCodec.BufferInfo mInfo = new MediaCodec.BufferInfo();
        private int mTrackIndex = -1;
        private boolean mMuxerStarted = false;
        private long mTotalFramesWritten = 0L;

        AacM4aSink(@NonNull final File outFile, final int sampleRate, final int channels) throws IOException {
            mSampleRate = sampleRate;
            mBytesPerFrame = channels * 2;

            final MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

            mEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mEncoder.start();

            mMuxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }

        void writePcm(@NonNull final byte[] data, final int length) {
            int offset = 0;

            while (offset < length) {
                final int inIdx = mEncoder.dequeueInputBuffer(0L);
                if (inIdx >= 0) {
                    final ByteBuffer inBuf = mEncoder.getInputBuffer(inIdx);
                    if (inBuf != null) {
                        inBuf.clear();
                        final int chunk = Math.min(inBuf.remaining(), length - offset);
                        inBuf.put(data, offset, chunk);
                        final long ptsUs = (mTotalFramesWritten * 1_000_000L) / mSampleRate;
                        mEncoder.queueInputBuffer(inIdx, 0, chunk, ptsUs, 0);
                        offset += chunk;
                        mTotalFramesWritten += chunk / mBytesPerFrame;
                    }
                }

                drain(false);
            }
        }

        long finish() {
            while (true) {
                final int inIdx = mEncoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
                if (inIdx >= 0) {
                    final long ptsUs = (mTotalFramesWritten * 1_000_000L) / mSampleRate;
                    mEncoder.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    break;
                }
                drain(false);
            }

            drain(true);
            return (mTotalFramesWritten * 1000L) / mSampleRate;
        }

        private void drain(final boolean endOfStream) {
            while (true) {
                final int outIdx = mEncoder.dequeueOutputBuffer(mInfo, endOfStream ? CODEC_TIMEOUT_US : 0L);

                if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream) {
                        break;
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    mTrackIndex = mMuxer.addTrack(mEncoder.getOutputFormat());
                    mMuxer.start();
                    mMuxerStarted = true;
                } else if (outIdx >= 0) {
                    final ByteBuffer outBuf = mEncoder.getOutputBuffer(outIdx);
                    if (
                            outBuf != null &&
                            mInfo.size > 0 &&
                            (mInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                            mMuxerStarted
                    ) {
                        outBuf.position(mInfo.offset);
                        outBuf.limit(mInfo.offset + mInfo.size);
                        mMuxer.writeSampleData(mTrackIndex, outBuf, mInfo);
                    }

                    mEncoder.releaseOutputBuffer(outIdx, false);
                    if ((mInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
        }

        void release() {
            try {
                mEncoder.stop();
            } catch (final RuntimeException ignored) {
            }
            mEncoder.release();

            if (mMuxerStarted) {
                try {
                    mMuxer.stop();
                } catch (final RuntimeException ignored) {
                }
            }
            mMuxer.release();
        }
    }

    // --- Pass 2: Offscreen RadioUI render → H.264 + mux with AAC ---

    private static final class FrameRig {
        final View root;
        final RecordingTunerBinder binder;
        final WaveformView waveform;
        final TextView timeCurrent;
        final int bgColor;
        final Bitmap frameBitmap;
        final Canvas frameCanvas;
        @Nullable
        RfmMetadataEvent lastEvent;
        int measuredW;
        int measuredH;

        FrameRig(
                @NonNull final View root,
                @NonNull final RecordingTunerBinder binder,
                @NonNull final WaveformView waveform,
                @NonNull final TextView timeCurrent,
                final int bgColor
        ) {
            this.root = root;
            this.binder = binder;
            this.waveform = waveform;
            this.timeCurrent = timeCurrent;
            this.bgColor = bgColor;
            this.frameBitmap = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888);
            this.frameCanvas = new Canvas(frameBitmap);
        }

        void release() {
            binder.release();
            frameBitmap.recycle();
        }
    }

    private static void encodeVideoOnly(
            @NonNull final Context themedContext,
            @NonNull final RecordingItem item,
            @NonNull final RfmMetadata metadata,
            final long totalDurationMs,
            @NonNull final File outVideoMp4,
            @NonNull final ProgressTracker tracker,
            @NonNull final CancelToken cancelToken
    ) throws IOException {
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final FrameRig rig = createFrameRigOnMain(mainHandler, themedContext, item, metadata, totalDurationMs);

        MediaCodec videoEncoder = null;
        EglSurfaceRenderer egl = null;
        MediaMuxer muxer = null;
        boolean muxerStarted = false;

        try {
            final MediaFormat videoFormat = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    VIDEO_WIDTH,
                    VIDEO_HEIGHT
            );
            videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE);
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_NOMINAL_FPS);
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            final Surface inputSurface = videoEncoder.createInputSurface();
            videoEncoder.start();

            egl = new EglSurfaceRenderer(inputSurface, VIDEO_WIDTH, VIDEO_HEIGHT);
            muxer = new MediaMuxer(outVideoMp4.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            final int[] trackIndex = new int[]{-1};
            final boolean[] startedFlag = new boolean[]{false};
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            final List<Long> frameTimestampsMs = buildFrameTimestamps(totalDurationMs);
            final int totalFrames = frameTimestampsMs.size();

            for (int i = 0; i < totalFrames; i++) {
                cancelToken.check();
                final long timeMs = frameTimestampsMs.get(i);
                renderUiBitmapOnMain(mainHandler, rig, metadata, timeMs, totalDurationMs);
                egl.renderFrame(rig.frameBitmap, timeMs * 1000L);

                drainSingleTrackEncoder(videoEncoder, muxer, trackIndex, startedFlag, info, false);

                final int pct = ((i + 1) * 100) / Math.max(1, totalFrames);
                tracker.setVideoPercent(pct);
            }

            cancelToken.check();
            videoEncoder.signalEndOfInputStream();
            drainSingleTrackEncoder(videoEncoder, muxer, trackIndex, startedFlag, info, true);
            muxerStarted = startedFlag[0];
        } finally {
            runOnMainSync(mainHandler, rig::release);

            if (egl != null) {
                egl.release();
            }
            if (videoEncoder != null) {
                try {
                    videoEncoder.stop();
                } catch (final RuntimeException ignored) {
                }
                videoEncoder.release();
            }
            if (muxer != null) {
                if (muxerStarted) {
                    try {
                        muxer.stop();
                    } catch (final RuntimeException ignored) {
                    }
                }
                muxer.release();
            }
        }
    }

    private static void drainSingleTrackEncoder(
            @NonNull final MediaCodec encoder,
            @NonNull final MediaMuxer muxer,
            @NonNull final int[] trackIndex,
            @NonNull final boolean[] startedFlag,
            @NonNull final MediaCodec.BufferInfo info,
            final boolean endOfStream
    ) {
        while (true) {
            final int outIdx = encoder.dequeueOutputBuffer(info, endOfStream ? CODEC_TIMEOUT_US : 0L);

            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) {
                    break;
                }
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!startedFlag[0]) {
                    trackIndex[0] = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    startedFlag[0] = true;
                }
            } else if (outIdx >= 0) {
                final ByteBuffer outBuf = encoder.getOutputBuffer(outIdx);
                if (
                        outBuf != null &&
                        info.size > 0 &&
                        (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                        startedFlag[0]
                ) {
                    outBuf.position(info.offset);
                    outBuf.limit(info.offset + info.size);
                    muxer.writeSampleData(trackIndex[0], outBuf, info);
                }

                encoder.releaseOutputBuffer(outIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }
    }

    private static void muxTracks(
            @NonNull final File videoOnlyMp4,
            @NonNull final File audioM4a,
            @NonNull final File outMp4,
            @NonNull final CancelToken cancelToken
    ) throws IOException {
        final MediaExtractor videoExtractor = new MediaExtractor();
        final MediaExtractor audioExtractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean muxerStarted = false;

        try {
            videoExtractor.setDataSource(videoOnlyMp4.getAbsolutePath());
            audioExtractor.setDataSource(audioM4a.getAbsolutePath());

            final MediaFormat videoFormat = selectFirstTrack(videoExtractor, "video/");
            final MediaFormat audioFormat = selectFirstTrack(audioExtractor, "audio/");
            if (videoFormat == null || audioFormat == null) {
                throw new IOException("Missing video or audio track during mux");
            }

            muxer = new MediaMuxer(outMp4.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            final int dstVideoTrack = muxer.addTrack(videoFormat);
            final int dstAudioTrack = muxer.addTrack(audioFormat);
            muxer.start();
            muxerStarted = true;

            final ByteBuffer copyBuf = ByteBuffer.allocate(262144);
            copyExtractorTrack(videoExtractor, muxer, dstVideoTrack, copyBuf, cancelToken);
            copyExtractorTrack(audioExtractor, muxer, dstAudioTrack, copyBuf, cancelToken);
        } finally {
            videoExtractor.release();
            audioExtractor.release();
            if (muxer != null) {
                if (muxerStarted) {
                    try {
                        muxer.stop();
                    } catch (final RuntimeException ignored) {
                    }
                }
                muxer.release();
            }
        }
    }

    @Nullable
    private static MediaFormat selectFirstTrack(
            @NonNull final MediaExtractor extractor,
            @NonNull final String mimePrefix
    ) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            final MediaFormat fmt = extractor.getTrackFormat(i);
            final String mime = fmt.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) {
                extractor.selectTrack(i);
                return fmt;
            }
        }
        return null;
    }

    private static void copyExtractorTrack(
            @NonNull final MediaExtractor extractor,
            @NonNull final MediaMuxer muxer,
            final int dstTrackIndex,
            @NonNull final ByteBuffer buffer,
            @NonNull final CancelToken cancelToken
    ) {
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            cancelToken.check();
            final int size = extractor.readSampleData(buffer, 0);
            if (size < 0) {
                break;
            }

            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = extractor.getSampleTime();
            info.flags = extractor.getSampleFlags();
            muxer.writeSampleData(dstTrackIndex, buffer, info);
            extractor.advance();
        }
    }

    @NonNull
    private static List<Long> buildFrameTimestamps(final long totalDurationMs) {
        final List<Long> stamps = new ArrayList<>();
        for (long t = 0L; t < totalDurationMs; t += FRAME_STEP_MS) {
            stamps.add(t);
        }

        if (stamps.isEmpty() || stamps.get(stamps.size() - 1) < totalDurationMs) {
            stamps.add(totalDurationMs);
        }

        return stamps;
    }

    @NonNull
    private static FrameRig createFrameRigOnMain(
            @NonNull final Handler mainHandler,
            @NonNull final Context themedContext,
            @NonNull final RecordingItem item,
            @NonNull final RfmMetadata metadata,
            final long totalDurationMs
    ) throws IOException {
        final AtomicReference<FrameRig> ref = new AtomicReference<>();
        final AtomicReference<RuntimeException> err = new AtomicReference<>();

        runOnMainSync(mainHandler, () -> {
            try {
                final View root = LayoutInflater.from(themedContext).inflate(R.layout.export_video_frame, null, false);
                final RecordingTunerBinder binder = new RecordingTunerBinder(root);
                binder.setAnimationsEnabled(false);
                final WaveformView waveform = root.findViewById(R.id.export_waveform);
                final TextView timeCurrent = root.findViewById(R.id.export_time_current);
                final TextView timeTotal = root.findViewById(R.id.export_time_total);
                final TextView caption = root.findViewById(R.id.export_caption);

                timeTotal.setText(Utils.getTimeStringBySeconds((int) Math.max(0L, totalDurationMs / 1000L)));
                caption.setText(item.getDisplayName());
                waveform.setPeaks(metadata.getWaveformPeaks());
                waveform.setMarkers(buildMarkers(metadata, totalDurationMs));

                final int bgColor = MaterialColors.getColor(
                        themedContext,
                        android.R.attr.colorBackground,
                        0xFF121212
                );

                ref.set(new FrameRig(root, binder, waveform, timeCurrent, bgColor));
            } catch (final RuntimeException e) {
                err.set(e);
            }
        });

        if (err.get() != null) {
            throw new IOException("Failed to initialize offscreen RadioUI", err.get());
        }

        return ref.get();
    }

    private static void renderUiBitmapOnMain(
            @NonNull final Handler mainHandler,
            @NonNull final FrameRig rig,
            @NonNull final RfmMetadata metadata,
            final long timeMs,
            final long totalDurationMs
    ) {
        runOnMainSync(mainHandler, () -> {
            final RfmMetadataEvent event = metadata.getEventAt(timeMs);
            final boolean needLayout = (rig.measuredW == 0 || event != rig.lastEvent);
            rig.lastEvent = event;

            rig.binder.bind(event);
            rig.timeCurrent.setText(Utils.getTimeStringBySeconds((int) Math.max(0L, timeMs / 1000L)));
            rig.waveform.setProgress(totalDurationMs > 0L ? (float) timeMs / (float) totalDurationMs : 0f);

            if (needLayout) {
                final float density = rig.root.getResources().getDisplayMetrics().density;
                final int measureW = Math.round(440f * density);
                final int wSpec = View.MeasureSpec.makeMeasureSpec(measureW, View.MeasureSpec.EXACTLY);
                final int hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
                rig.root.measure(wSpec, hSpec);

                rig.measuredW = Math.max(1, rig.root.getMeasuredWidth());
                rig.measuredH = Math.max(1, rig.root.getMeasuredHeight());
                rig.root.layout(0, 0, rig.measuredW, rig.measuredH);
            }

            rig.frameCanvas.drawColor(rig.bgColor);
            final float margin = 8f;
            final float scale = Math.min(
                    (VIDEO_WIDTH - 2f * margin) / rig.measuredW,
                    (VIDEO_HEIGHT - 2f * margin) / rig.measuredH
            );
            final float dx = (VIDEO_WIDTH - rig.measuredW * scale) / 2f;
            final float dy = (VIDEO_HEIGHT - rig.measuredH * scale) / 2f;

            rig.frameCanvas.save();
            rig.frameCanvas.translate(dx, dy);
            rig.frameCanvas.scale(scale, scale);
            rig.root.draw(rig.frameCanvas);
            rig.frameCanvas.restore();
        });
    }

    @NonNull
    private static List<WaveformView.Marker> buildMarkers(
            @NonNull final RfmMetadata metadata,
            final long totalDurationMs
    ) {
        final List<WaveformView.Marker> markers = new ArrayList<>();
        if (totalDurationMs <= 0L) {
            return markers;
        }

        int lastFreq = -1;
        for (final RfmMetadataEvent event : metadata.getEvents()) {
            final int freq = event.getSnapshot().getFrequencyKhz();
            if (lastFreq > 0 && freq > 0 && freq != lastFreq && event.getTimeMs() > 0L) {
                final float fraction = (float) event.getTimeMs() / (float) totalDurationMs;
                final String label = freq % 100 == 0
                        ? String.format(Locale.US, "%.1f", freq / 1000f)
                        : String.format(Locale.US, "%.2f", freq / 1000f);
                markers.add(new WaveformView.Marker(fraction, label));
            }
            if (freq > 0) {
                lastFreq = freq;
            }
        }

        return markers;
    }

    // --- Pass 3: Publish MP4 ---

    @NonNull
    private static ExportResult publishMp4(
            @NonNull final Context context,
            @NonNull final File tempMp4,
            @NonNull final String displayName
    ) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            final ContentResolver resolver = context.getContentResolver();
            final ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + MOVIES_SUBDIR);
            values.put(MediaStore.Video.Media.IS_PENDING, 1);

            final Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            final Uri uri = resolver.insert(collection, values);
            if (uri == null) {
                throw new IOException("Cannot insert video into MediaStore");
            }

            try {
                try (
                        InputStream in = new FileInputStream(tempMp4);
                        OutputStream out = resolver.openOutputStream(uri)
                ) {
                    if (out == null) {
                        throw new IOException("Cannot open MediaStore video stream");
                    }
                    copyStream(in, out);
                }

                final ContentValues done = new ContentValues();
                done.put(MediaStore.Video.Media.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
                return new ExportResult(displayName, uri, null);
            } catch (final IOException error) {
                resolver.delete(uri, null, null);
                throw error;
            }
        }

        final File moviesDir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                MOVIES_SUBDIR
        );
        if (!moviesDir.exists() && !moviesDir.mkdirs()) {
            throw new IOException("Cannot create Movies/RFM-Recordings directory");
        }

        final File outFile = new File(moviesDir, displayName);
        try (
                InputStream in = new FileInputStream(tempMp4);
                OutputStream out = new FileOutputStream(outFile)
        ) {
            copyStream(in, out);
        }

        return new ExportResult(displayName, null, outFile.getAbsolutePath());
    }

    @NonNull
    private static String buildOutputName(@NonNull final String sourceDisplayName) {
        final int dot = sourceDisplayName.lastIndexOf('.');
        final String base = dot > 0 ? sourceDisplayName.substring(0, dot) : sourceDisplayName;
        return base + ".mp4";
    }

    // --- Helpers ---

    private static void runOnMainSync(@NonNull final Handler mainHandler, @NonNull final Runnable task) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
            return;
        }

        final CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                task.run();
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (final InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class CancelToken {
        @Nullable
        private final AtomicBoolean mExternal;
        @NonNull
        private final AtomicBoolean mInternal;

        CancelToken(@Nullable final AtomicBoolean external, @NonNull final AtomicBoolean internal) {
            mExternal = external;
            mInternal = internal;
        }

        void check() {
            if ((mExternal != null && mExternal.get()) || mInternal.get()) {
                throw new CancellationException("Video export cancelled");
            }
        }
    }

    private static final class ProgressTracker {
        @Nullable
        private final ProgressListener mListener;
        private final AtomicInteger mAudio = new AtomicInteger(0);
        private final AtomicInteger mVideo = new AtomicInteger(0);
        private final AtomicBoolean mSaving = new AtomicBoolean(false);
        private int mLastAudio = -1;
        private int mLastVideo = -1;
        private boolean mLastSaving = false;

        ProgressTracker(@Nullable final ProgressListener listener) {
            mListener = listener;
        }

        void setAudioPercent(final int pct) {
            mAudio.set(Math.max(0, Math.min(100, pct)));
            emit();
        }

        void setVideoPercent(final int pct) {
            mVideo.set(Math.max(0, Math.min(100, pct)));
            emit();
        }

        void setSaving() {
            mSaving.set(true);
            emit();
        }

        synchronized void emit() {
            if (mListener == null) {
                return;
            }

            final int a = mAudio.get();
            final int v = mVideo.get();
            final boolean s = mSaving.get();

            if (a != mLastAudio || v != mLastVideo || s != mLastSaving) {
                mLastAudio = a;
                mLastVideo = v;
                mLastSaving = s;
                mListener.onProgress(a, v, s);
            }
        }
    }

    private static boolean readChunkHeader(@NonNull final InputStream in, @NonNull final byte[] header) throws IOException {
        int off = 0;
        while (off < header.length) {
            final int n = in.read(header, off, header.length - off);
            if (n < 0) {
                return false;
            }
            off += n;
        }
        return true;
    }

    private static void readFully(@NonNull final InputStream in, @NonNull final byte[] buf, final int len) throws IOException {
        int off = 0;
        while (off < len) {
            final int n = in.read(buf, off, len - off);
            if (n < 0) {
                throw new IOException("Unexpected EOF");
            }
            off += n;
        }
    }

    private static void skipFully(@NonNull final InputStream in, final long bytes) throws IOException {
        long rem = bytes;
        while (rem > 0L) {
            final long skipped = in.skip(rem);
            if (skipped <= 0L) {
                if (in.read() < 0) {
                    throw new IOException("Unexpected EOF while skipping");
                }
                rem--;
            } else {
                rem -= skipped;
            }
        }
    }

    private static void copyStream(@NonNull final InputStream in, @NonNull final OutputStream out) throws IOException {
        final byte[] buf = new byte[32768];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }
}
