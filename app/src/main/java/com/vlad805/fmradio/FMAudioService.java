package com.vlad805.fmradio;

import android.content.Context;
import android.media.*;

import static com.vlad805.fmradio.Utils.sleep;

/**
 * vlad805 (c) 2019
 */
@SuppressWarnings("deprecation")
public class FMAudioService {

	private AudioManager mAudioManager;
	private final int mChannelsCount = 2; // Количество аудиоканалов
	private int mSampleRate = 44100; // Default = 8000 (Max with AMR)
	private int mRecorderSource = 0;

	private int m_hw_size = 16384;
	private int at_min_size = 4096;

	private AudioTrack mAudioTrack = null;
	private AudioRecord mAudioRecorder = null;
	private Thread pcm_write_thread = null;
	private Thread pcm_read_thread = null;

	private boolean pcm_write_thread_active = false;
	private boolean pcm_read_thread_active = false;

	public FMAudioService(Context context) {
		mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
	}

	public void startAudio() {
		sleep(500);
		m_hw_size = AudioRecord.getMinBufferSize(mSampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
		//at_min_size = AudioTrack.getMinBufferSize(mSampleRate, mOutChannelId, AudioFormat.ENCODING_PCM_16BIT);

		requestForFocus(true);

		pcm_write_start();
		pcm_read_start();
	}

	public void stopAudio() {
		pcm_write_stop();
		pcm_read_stop();
	}


	private void pcm_write_start() {
		try {
			mAudioTrack = new AudioTrack(
					AudioManager.STREAM_MUSIC,
					mSampleRate,
					AudioFormat.CHANNEL_OUT_STEREO,
					AudioFormat.ENCODING_PCM_16BIT,
					at_min_size,
					AudioTrack.MODE_STREAM
			);

			mAudioTrack.play(); // java.lang.IllegalStateException: play() called on uninitialized AudioTrack.
		} catch (Throwable e) {
			e.printStackTrace();
			return;
		}

		if (pcm_write_thread_active) {
			return;
		}

		pcm_write_thread_active = true;
		pcm_write_thread = new Thread(pcm_write_runnable, "pcm_write");
		pcm_write_thread.start();
	}

	private void pcm_write_stop() {
		pcm_write_thread_active = false;
		pcm_write_thread.interrupt();
	}

	private void pcm_read_start() {
		if (pcm_read_thread_active) {
			return;
		}

		mAudioRecorder = getAudioRecorder();

		if (mAudioRecorder == null) {
			return;
		}

		mAudioRecorder.startRecording();

		//mApi.setAudioSessionId(mAudioRecorder.getAudioSessionId());

		pcm_read_thread_active = true;
		pcm_read_thread = new Thread(pcm_read_runnable, "pcm_read");
		pcm_read_thread.start();
	}

	private int aud_buf_tail = 0;
	private int aud_buf_head = 0;
	private int aud_buf_num = 32;

	private static final int pcm_size_max = 65365;

	private int min_pcm_write_buffers = 1;
	private boolean pcm_write_thread_waiting = false;

	private int max_bufs = 0;

	private byte[][] aud_buf_data = new byte[32][];
	private int[] aud_buf_len = new int[32];

	private Runnable pcm_read_runnable = new Runnable() {
		@Override
		public void run() {
			/*byte[] buffer = new byte[m_hw_size];
			while (pcm_read_thread_active) {
				int len = mAudioRecorder.read(buffer, 0, m_hw_size);
				mAudioTrack.write(buffer, 0, len);
			}*/
			int buf_errs = 0; // Init stats, pointers, etc
			aud_buf_tail = aud_buf_head = 0; // Drop all buffers
			while (pcm_read_thread_active) { // While PCM Read Thread should be active...
				if (aud_buf_read(mSampleRate, mChannelsCount, m_hw_size)) { // Fill a PCM read buffer, If filled...
					int bufs = aud_buf_tail - aud_buf_head;
					//log("pcm_read_runnable run() bufs: " + bufs + "  tail: " + aud_buf_tail + "  head: " + aud_buf_head);

					if (bufs < 0) {
						bufs += aud_buf_num; // Fix underflow
					}

					if (bufs >= min_pcm_write_buffers) { // If minimum number of buffers is ready... (Currently at least 2)
						if (pcm_write_thread != null && pcm_write_thread_waiting) {
							pcm_write_thread.interrupt(); // Wake up pcm_write_thread sooner than usual
						}
					}
				}
				// Else, if no data could be retrieved wait 50 milliseconds for errors to clear
			}
		}
	};

	private Runnable pcm_write_runnable = new Runnable() {
		@Override
		public void run() {
			while (pcm_write_thread_active) { // While PCM Write Thread should be active...
				int bufs = aud_buf_tail - aud_buf_head;

				if (bufs < 0) {
					bufs += aud_buf_num; // Fix underflow
				}

				// If minimum number of buffers is not ready... (Currently at least 2)
				if (bufs < min_pcm_write_buffers) {
					try {
						pcm_write_thread_waiting = true;
						Thread.sleep(2);  // Wait ms milliseconds 3 matches Spirit1
						pcm_write_thread_waiting = false;
					} catch (InterruptedException e) {
						pcm_write_thread_waiting = false;
					}

					continue; // Restart loop
				}

				//log("pcm_write_runnable run() ready to write bufs: " + bufs + "  tail: " + aud_buf_tail + "  head: " + aud_buf_head);

				int len = aud_buf_len[aud_buf_head]; // Length of head buffer in bytes
				byte[] aud_buf = aud_buf_data[aud_buf_head]; // Pointer to head buffer

				int len_written;
				int new_len;

				long total_ms_start = tmr_ms_get();
				long total_ms_time = -1;
				len_written = 0;

				// Write head buffer to audiotrack  All parameters in bytes (but could be all in shorts)
				while (pcm_write_thread_active && len_written < len && total_ms_time < 3000) {

					if (total_ms_time >= 0) {
						sleep(30);
					}

					if (!pcm_write_thread_active) {
						break;
					}

					new_len = mAudioTrack.write(aud_buf, 0, len);
					if (new_len > 0) {
						len_written += new_len;
					}

					total_ms_time = tmr_ms_get() - total_ms_start;

					//log("pcm_write_runnable run() len_written: " + len_written);

					// Largest value 0xFFFFFFFC = 4294967292 max total file size, so max data size = 4294967256 = 1073741814 samples (0x3FFFFFF6)
					//wav_write_bytes (wav_header, 0x04, 4, audiorecorder_data_size + 36);
					// Chunksize = total filesize - 8 = DataSize + 36

					aud_buf_head++;
					if (aud_buf_head < 0 || aud_buf_head > aud_buf_num - 1) {
						aud_buf_head &= aud_buf_num - 1;
					}
				}

				// Restart loop
			}
		}
	};

	public static long tmr_ms_get() {
		return System.nanoTime() / 1000000;
	}

	private boolean aud_buf_read(int samplerate, int channels, int len_max) {
		int bufs = aud_buf_tail - aud_buf_head;

		if (bufs < 0) { // If underflowed...
			bufs += aud_buf_num; // Wrap
		}

		if (bufs > max_bufs) { // If new maximum buffers in progress...
			max_bufs = bufs; // Save new max
		}

		if (bufs >= (aud_buf_num * 3) / 4) {
			sleep(300);     // 0.1s = 20KBytes @ 48k stereo  (2.5 8k buffers)
		}

		if (bufs >= aud_buf_num - 1) { // If NOT 6 or less buffers in progress, IE if room to write another (max = 7)
			aud_buf_tail = aud_buf_head = 0; // Drop all buffers
		}

		//int buf_tail = aud_buf_tail;  !!
		int len = -555;

		try {
			if (aud_buf_data[aud_buf_tail] == null) {
				// Allocate memory to pcm_size_max. Could use len_max but that prevents live tuning unless re-allocate while running.
				aud_buf_data[aud_buf_tail] = new byte[pcm_size_max];
			}

			if (mAudioRecorder != null) {
				len = mAudioRecorder.read(aud_buf_data[aud_buf_tail], 0, len_max);
			}
		} catch (Throwable e) {
			e.printStackTrace();
		}

		if (len <= 0) {
			sleep(500);
			return false;
		}


		// Protect from ArrayIndexOutOfBoundsException
		if (aud_buf_tail < 0 || aud_buf_tail > aud_buf_num - 1) {
			aud_buf_tail &= aud_buf_num - 1;
		}

		// On shutdown: java.lang.ArrayIndexOutOfBoundsException: length=32; index=32
		aud_buf_len[aud_buf_tail] = len;

		aud_buf_tail++;

		if (aud_buf_tail < 0 || aud_buf_tail > aud_buf_num - 1) {
			aud_buf_tail &= aud_buf_num - 1;
		}

		//aud_buf_tail = buf_tail;

		return true;
	}


	private void pcm_read_stop() {
		pcm_read_thread_active = false;
		pcm_read_thread.interrupt();

		if (mAudioRecorder == null) {
			return;
		}

		mAudioRecorder.stop();
		mAudioRecorder.release();
		mAudioRecorder = null;
	}

	private AudioRecord getAudioRecorder() {
		/**
		 * DEFAULT              пашет, качественный микрофон, тихий
		 * MIC                  пашет, качественный микрофон
		 * VOICE_UPLINK         не пашет
		 * VOICE_DOWNLINK       не пашет
		 * VOICE_CALL           не пашет
		 * CAMCORDER            пашет, микрофон говно, но очень чувствительный
		 * VOICE_RECOGNITION    пашет, микрофон говно, только левый канал
		 * VOICE_COMMUNICATION  пашет, микрофон говно, но очень чувствительный
		 * REMOTE_SUBMIX        не пашет
		 */
		int audioSource = 1998; //1998;
		// AUDIO_CHANNEL_IN_FRONT_BACK?

		try {
			AudioRecord recorder = new AudioRecord(
					audioSource,
					mSampleRate,
					AudioFormat.CHANNEL_IN_STEREO,
					AudioFormat.ENCODING_PCM_16BIT,
					m_hw_size
			);

			if (recorder.getState() == AudioRecord.STATE_INITIALIZED) { // If works, then done
				mRecorderSource = audioSource;
				return recorder;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	private void requestForFocus(boolean needFocus) {
		if (needFocus) { // If focus desired...
			mAudioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK); //AudioManager.AUDIOFOCUS_GAIN);
		} else { // If focus return...
			mAudioManager.abandonAudioFocus(null);
		}
	}

}
