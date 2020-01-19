package com.vlad805.fmradio.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.controller.FavoriteController;
import com.vlad805.fmradio.helper.RecyclerItemClickListener;
import com.vlad805.fmradio.helper.Toast;
import com.vlad805.fmradio.models.FavoriteStation;

import java.io.FileNotFoundException;
import java.util.List;

public class FavoritesListsActivity extends Activity implements AdapterView.OnItemSelectedListener {
	private FavoriteController mController;
	private Spinner mSpinner;
	private List<String> mFavoriteListNames;

	private RecyclerView mRecycler;
	private FavoriteAdapter mAdapter;
	private List<FavoriteStation> mStationsList;

	private Toast mToast;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_favorites_lists);


		mToast = Toast.create(this);
		initLogic();
		initUI();
	}

	private void initLogic() {
		mController = new FavoriteController(this);
	}

	private void initUI() {
		mSpinner = findViewById(R.id.favorite_list_lists);

		mFavoriteListNames = mController.getFavoriteLists();

		mRecycler = findViewById(R.id.favorite_list_content);
		mRecycler.addOnItemTouchListener(new RecyclerItemClickListener(this, mRecycler, new RecyclerItemClickListener.OnItemClickListener() {
			@Override
			public void onItemClick(View view, int position) {

			}

			@Override
			public void onLongItemClick(View view, int position) {

			}
		}));

		mAdapter = new FavoriteAdapter(mStationsList);
		mRecycler.setAdapter(mAdapter);


		reloadLists();
		reloadContent();

		// Listen this only after setSelection
		mSpinner.setOnItemSelectedListener(this);
	}

	private void reloadLists() {
		mFavoriteListNames = mController.getFavoriteLists();

		String current = mController.getCurrentFavoriteList();
		int position = mFavoriteListNames.indexOf(current);

		mToast.text("current " + current + ", position " + position).show();

		ArrayAdapter<?> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, mFavoriteListNames.toArray(new String[0]));

		mSpinner.setAdapter(adapter);
		mSpinner.setSelection(position);
	}

	private void reloadContent() {
		if (mStationsList != null) {
			mStationsList.clear();
		}

		mStationsList = mController.getStationsInCurrentList();
		mAdapter.setDataset(mStationsList);
		mAdapter.notifyDataSetChanged();
	}

	@Override
	public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
		String item = (String) parent.getItemAtPosition(position);

		try {
			mController.setCurrentFavoriteList(item);
			reloadContent();

			Intent intent = new Intent().putExtra("changed", true);
			setResult(Activity.RESULT_OK, intent);
		} catch (FileNotFoundException e) {
			mToast.text("Not found this list").show();
		}
	}

	@Override
	public void onNothingSelected(AdapterView<?> parent) {}

	static class FavoriteHolder extends RecyclerView.ViewHolder {

		private TextView mFrequency;
		private TextView mTitle;

		public FavoriteHolder(View v) {
			super(v);
			mFrequency = v.findViewById(R.id.station_item_frequency);
			mTitle = v.findViewById(R.id.station_item_title);
		}

		public void set(FavoriteStation s) {
			mFrequency.setText(Utils.getMHz(s.getFrequency()).trim());
			mTitle.setText(s.getTitle());
		}
	}

	static class FavoriteAdapter extends RecyclerView.Adapter<FavoriteHolder> {

		private List<FavoriteStation> mDataset;

		public FavoriteAdapter(List<FavoriteStation> dataset) {
			setDataset(dataset);
		}

		public void setDataset(List<FavoriteStation> dataset) {
			mDataset = dataset;
		}

		@Override
		@NonNull
		public FavoriteHolder onCreateViewHolder(ViewGroup parent, int viewType) {
			View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.station_list_item, parent, false);

			return new FavoriteHolder(v);
		}

		@Override
		public void onBindViewHolder(FavoriteHolder holder, int position) {
			holder.set(mDataset.get(position));
		}

		@Override
		public int getItemCount() {
			return mDataset.size();
		}
	}
}
