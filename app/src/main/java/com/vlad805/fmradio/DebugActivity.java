package com.vlad805.fmradio;

import android.app.ActionBar;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

public class DebugActivity extends Activity implements View.OnClickListener {

	private PlayerReceiver mStatusReceiver;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_debug);

		int[] ids = {
				R.id.btn_start,
				R.id.btn_stop,
				R.id.btn_set,
				R.id.btn_set_stereo,
				R.id.btn_kill,
				R.id.btn_seek_up,
				R.id.btn_seek_down,
				R.id.btn_search
		};

		for (int id : ids) {
			findViewById(id).setOnClickListener(this);
		}

		mStatusReceiver = new PlayerReceiver();

		FM.send(this, C.FM_INIT);
	}

	@Override
	protected void onResume() {
		super.onResume();

		IntentFilter filter = new IntentFilter();
		filter.addAction(C.Event.FREQUENCY_SET);
		filter.addAction(C.Event.UPDATE_PS);
		filter.addAction(C.Event.UPDATE_RSSI);
		filter.addAction(C.Event.SEARCH_DONE);
	//	filter.addAction(C.FETCHED_RSSI);
		registerReceiver(mStatusReceiver, filter);
	}

	@Override
	protected void onPause() {
		unregisterReceiver(mStatusReceiver);

		super.onPause();
	}

	@Override
	public void onClick(View v) {
		switch (v.getId()) {
			case R.id.btn_start: start(); break;
			case R.id.btn_stop: stop(); break;
			case R.id.btn_set: setFrequency(); break;
			case R.id.btn_set_stereo: setStereo(); break;
			case R.id.btn_kill: kill(); break;
			case R.id.btn_search: search(); break;
			case R.id.btn_seek_up: seek(1); break;
			case R.id.btn_seek_down: seek(0); break;
			case R.id.btn_mute_none: mute(MuteState.NONE); break;
			case R.id.btn_mute_left: mute(MuteState.LEFT); break;
			case R.id.btn_mute_right: mute(MuteState.RIGHT); break;
			case R.id.btn_mute_both: mute(MuteState.BOTH); break;
		}

	}

	private void start() {
		FM.send(this, C.FM_ENABLE);
	}

	private void stop() {
		FM.send(this, C.FM_DISABLE);
	}

	private void setFrequency() {
		FM.send(this, C.FM_SET_FREQUENCY,
				C.KEY_FREQUENCY, ((EditText) findViewById(R.id.freq)).getText().toString()
		);
	}

	private void setStereo() {
		FM.send(this, C.FM_SET_STEREO);
	}

	private void search() {
		FM.send(this, C.FM_SEARCH);
	}

	private void kill() {
		FM.send(this, C.FM_KILL);
	}

	private void seek(int dir) {
		FM.send(this, C.FM_HW_SEEK,
				C.FM_KEY_SEEK_HW_DIRECTION, String.valueOf(dir)
		);
	}

	private void mute(MuteState state) {
		FM.send(this, C.FM_SET_MUTE,
				C.KEY_MUTE, state.name()
		);
	}

	//public native int set_priority(int new_priority);

	public class PlayerReceiver extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, final Intent intent) {
			if (intent == null || intent.getAction() == null) {
				return;
			}

			switch (intent.getAction()) {
				case C.Event.UPDATE_RSSI:
					setText(R.id.text_rssi, String.valueOf(intent.getIntExtra(C.KEY_RSSI, -2)));
					break;

				case C.Event.FREQUENCY_SET:
					runOnUiThread(new Runnable() {
						@Override
						public void run() {
							setText(R.id.freq, String.valueOf(intent.getIntExtra(C.KEY_FREQUENCY, -1)));
						}
					});
					break;

				case C.Event.UPDATE_PS:
					runOnUiThread(new Runnable() {
						@Override
						public void run() {
							setText(R.id.text_ps, intent.getStringExtra(C.KEY_PS));
						}
					});

					ActionBar ab = getActionBar();

					if (ab != null) {
						ab.setSubtitle(intent.getStringExtra(C.KEY_PS));
					}
					break;

			}
		}
	}

	private void setText(int id, String data) {
		((TextView) findViewById(id)).setText(data);
	}
}
