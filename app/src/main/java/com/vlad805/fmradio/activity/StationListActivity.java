package com.vlad805.fmradio.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.*;
import android.widget.PopupMenu;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.db.AppDatabase;
import com.vlad805.fmradio.db.Station;
import com.vlad805.fmradio.db.StationDao;
import com.vlad805.fmradio.helper.RecyclerItemClickListener;
import com.vlad805.fmradio.helper.RenameDialog;
import com.vlad805.fmradio.service.FM;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class StationListActivity extends Activity {

	private RecyclerView mRecycler;

	private RecyclerView.Adapter mAdapter;

	private AlertDialog mAlert;

	private AppDatabase mDatabase;

	private List<Station> mList;

	private interface OnListReady {
		void onReady(List<Station> stations);
	}

	private OnListReady mShow = new OnListReady() {
		@Override
		public void onReady(List<Station> stations) {

			if (mAlert != null && mAlert.isShowing()) {
				mAlert.cancel();
			}

			mList = stations;

			mAdapter = new StationAdapter(stations);
			mRecycler.setAdapter(mAdapter);

			saveStations(stations);
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_station_list);

		mRecycler = findViewById(R.id.station_list_recycler);

		mRecycler.addOnItemTouchListener(new RecyclerItemClickListener(this, mRecycler, new RecyclerItemClickListener.OnItemClickListener() {
			@Override
			public void onItemClick(View view, int position) {
				Station station = mList.get(position);

				FM.send(StationListActivity.this, C.Command.SET_FREQUENCY,
						C.Key.FREQUENCY, String.valueOf(station.getFrequency())
				);
			}

			@Override
			public void onLongItemClick(View view, int position) {
				showPopup(view, position);
			}
		}));

		mDatabase = AppDatabase.getInstance(this);

		getStations(mShow);
	}

	/**
	 * Load list of stations from database
	 * @param onReady Callback on UI thread
	 */
	private void getStations(final OnListReady onReady) {
		new Thread(() -> {
			final List<Station> result = mDatabase.stationDao().getAll();
			runOnUiThread(() -> onReady.onReady(result));
		}).start();
	}

	private void saveStations(final List<Station> list) {
		new Thread(() -> {
			StationDao dao = mDatabase.stationDao();
			dao.markAllAsOld();
			dao.add(list);
			dao.removeOld();
		}).start();
	}

	private void updateStation(final Station station) {
		new Thread(() -> mDatabase.stationDao().update(station)).start();
	}

	private void removeStation(final Station station) {
		new Thread(() -> mDatabase.stationDao().delete(station)).start();
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

	private static final int MENU_RENAME = 1401;
	private static final int MENU_REMOVE = 1402;

	private void showPopup(final View v, final int position) {
		final Station station = mList.get(position);

		final PopupMenu popupMenu = new PopupMenu(this, v);
		final Menu menu = popupMenu.getMenu();
		menu.add(1, MENU_RENAME, 1, getString(R.string.popup_station_rename));
		menu.add(1, MENU_REMOVE, 2, getString(R.string.popup_station_remove));

		popupMenu.setOnMenuItemClickListener(item -> {
			switch (item.getItemId()) {
				case MENU_REMOVE:
					removeStation(station);
					mList.remove(station);
					mAdapter.notifyItemRemoved(position);
					break;

				case MENU_RENAME:
					new RenameDialog(StationListActivity.this, station.getTitle(), title -> {
						station.setTitle(title);
						updateStation(station);
						mAdapter.notifyItemChanged(position);
					}).setTitle(R.string.popup_station_rename).open();
					break;
			}
			return true;
		});

		popupMenu.show();
	}

	static class StationHolder extends RecyclerView.ViewHolder {

		private TextView mFrequency;
		private TextView mTitle;

		public StationHolder(View v) {
			super(v);
			mFrequency = v.findViewById(R.id.station_item_frequency);
			mTitle = v.findViewById(R.id.station_item_title);
		}

		public void set(Station s) {
			mFrequency.setText(String.format(Locale.ENGLISH, "%.1f MHz", s.getFrequency() / 1000f));
			mTitle.setText(s.getTitle());
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
		if (item.getItemId() == MENU_REFRESH) {
			search(mShow);
		}
		return super.onOptionsItemSelected(item);
	}
}
