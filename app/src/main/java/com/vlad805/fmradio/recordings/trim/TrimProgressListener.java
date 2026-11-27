package com.vlad805.fmradio.recordings.trim;

/** Progress callback for recording trim operations ({@code 0..100}). */
public interface TrimProgressListener {
    void onProgress(int percent);
}
