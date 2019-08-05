package com.vlad805.fmradio.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.os.Bundle;
import android.util.Log;
import android.view.*;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.service.FM;
import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class StationListActivity extends Activity {

	private RecyclerView mRecycler;

	private RecyclerView.Adapter mAdapter;

	private AlertDialog mAlert;

	private interface OnListReady {
		void onReady(List<Station> stations);
	}

	private OnListReady mShow = new OnListReady() {
		@Override
		public void onReady(List<Station> stations) {

			if (mAlert != null && mAlert.isShowing()) {
				mAlert.cancel();
			}

			mAdapter = new StationAdapter(stations);
			mRecycler.setAdapter(mAdapter);


			String json;

			JSONArray arr = new JSONArray();

			for (Station station : stations) {
				arr.put(station.getFrequency());
			}

			json = arr.toString();

			Storage.getInstance(StationListActivity.this).edit().putString(C.PrefKey.STATIONS_DATA_LIST, json).apply();
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_station_list);

		mRecycler = findViewById(R.id.station_list_recycler);

		fetchStations(mShow);
	}

	public void fetchStations(OnListReady onReady) {
		SharedPreferences sp = Storage.getInstance(this);

		if (!sp.contains(C.PrefKey.STATIONS_DATA_LIST)) {
			search(onReady);
		} else {
			List<Station> list = new ArrayList<>();

			String json = sp.getString(C.PrefKey.STATIONS_DATA_LIST, C.PrefDefaultValue.STATIONS_DATA_LIST);
			Log.d("SLA", "fetchStations: " + json);
			try {
				JSONArray jsonArray = new JSONArray(json);

				for (int i = 0, l = jsonArray.length(); i < l; ++i) {
					int val = jsonArray.optInt(i, -1);
					if (val != -1) {
						list.add(new Station(val));
					}
				}
			} catch (JSONException e) {
				e.printStackTrace();
			} finally {
				onReady.onReady(list);
			}
		}
	}

	static class ResultListener extends BroadcastReceiver {

		private OnListReady mListener;
		private Context mCtx;

		public ResultListener(OnListReady listener, Context ctx) {
			mListener = listener;
			mCtx = ctx;
		}

		@Override
		public void onReceive(Context context, Intent intent) {
			mCtx.unregisterReceiver(this);

			List<Station> res = new ArrayList<>();
			int[] list = intent.getIntArrayExtra(C.Key.STATION_LIST);

			for (int frequency : list) {
				res.add(new Station(frequency));
			}

			mListener.onReady(res);
		}
	}

	private void search(OnListReady listener) {
		final IntentFilter filter = new IntentFilter();
		filter.addAction(C.Event.SEARCH_DONE);

		AlertDialog.Builder ab = new AlertDialog.Builder(this)
				.setTitle(R.string.station_list_search)
				.setMessage(R.string.station_list_search_message)
				.setCancelable(false)
				.setIcon(R.drawable.ic_search);

		mAlert = ab.create();
		mAlert.show();

		registerReceiver(new ResultListener(listener, this), filter);
		FM.send(this, C.Command.SEARCH);
	}

	static class Station {
		private int frequency;

		public Station(int frequency) {
			this.frequency = frequency;
		}

		public int getFrequency() {
			return frequency;
		}
	}

	static class StationHolder extends RecyclerView.ViewHolder {

		private TextView mFrequency;

		public StationHolder(View v) {
			super(v);
			mFrequency = v.findViewById(R.id.station_item_frequency);
		}

		public void set(Station s) {
			mFrequency.setText(String.format(Locale.ENGLISH, "%5.1f MHz", s.getFrequency() / 1000f));
		}
	}

	static class StationAdapter extends RecyclerView.Adapter<StationHolder> {

		private List<Station> mDataset;

		public StationAdapter(List<Station> myDataset) {
			mDataset = myDataset;
		}

		@Override
		@NonNull
		public StationHolder onCreateViewHolder(ViewGroup parent, int viewType) {
			View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.station_list_item, parent, false);

			return new StationHolder(v);
		}

		@Override
		public void onBindViewHolder(StationHolder holder, int position) {
			holder.set(mDataset.get(position));
		}

		@Override
		public int getItemCount() {
			return mDataset.size();
		}
	}


	private static final int MENU_REFRESH = 14897;

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {

		MenuItem refresh = menu.add(0, MENU_REFRESH, 1, "Refresh");
		refresh.setIcon(R.drawable.ic_refresh);
		refresh.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case MENU_REFRESH:
				search(mShow);
				break;
		}
		return super.onOptionsItemSelected(item);
	}
}
