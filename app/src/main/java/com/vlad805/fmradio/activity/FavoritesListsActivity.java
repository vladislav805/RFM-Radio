package com.vlad805.fmradio.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.controller.FavoriteController;
import com.vlad805.fmradio.helper.EditTextDialog;
import com.vlad805.fmradio.helper.RecyclerItemClickListener;
import com.vlad805.fmradio.helper.Toast;
import com.vlad805.fmradio.models.FavoriteStation;

import java.io.FileNotFoundException;
import java.util.List;

public class FavoritesListsActivity extends Activity implements AdapterView.OnItemSelectedListener {
	private Menu mMenu;
	private String mCurrentNameList;

	private FavoriteController mController;
	private Spinner mSpinner;
	private List<String> mFavoriteListNames;

	private RecyclerView mRecycler;
	private FavoriteAdapter mAdapter;
	private List<FavoriteStation> mStationsList;

	private Toast mToast;

	private void setTitle(String title) {
		if (getActionBar() != null) {
			getActionBar().setTitle(title);
		}
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_favorites_lists);

		mToast = Toast.create(this);
		initLogic();
		initUI();
	}

	/**
	 * Create controller for get features controller
	 */
	private void initLogic() {
		mController = new FavoriteController(this);
	}

	/**
	 * Initialize UI
	 */
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

	/**
	 * Update list of favorite lists in spinner
	 */
	private void reloadLists() {
		mFavoriteListNames = mController.getFavoriteLists();

		mCurrentNameList = mController.getCurrentFavoriteList();
		int position = mFavoriteListNames.indexOf(mCurrentNameList);

		ArrayAdapter<?> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, mFavoriteListNames.toArray(new String[0]));

		mSpinner.setAdapter(adapter);
		mSpinner.setSelection(position);
	}

	/**
	 * Update list of stations when favorite list selected
	 */
	private void reloadContent() {
		if (mStationsList != null) {
			mStationsList.clear();
		}

		mStationsList = mController.getStationsInCurrentList();
		setTitle(mCurrentNameList);
		mAdapter.setDataset(mStationsList);
		mAdapter.notifyDataSetChanged();

		if (mMenu != null) {
			mMenu.findItem(R.id.menu_favorite_remove).setVisible(!mCurrentNameList.equals(FavoriteController.DEFAULT_NAME));
		}
	}

	/**
	 * Listener for selected item in spinner (favorite list)
	 */
	@Override
	public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
		String item = (String) parent.getItemAtPosition(position);

		try {
			mController.setCurrentFavoriteList(item);
			mCurrentNameList = item;
			reloadContent();

			Intent intent = new Intent().putExtra("changed", true);
			setResult(Activity.RESULT_OK, intent);
		} catch (FileNotFoundException e) {
			mToast.text("Not found this list").show();
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		super.onCreateOptionsMenu(menu);
		mMenu = menu;
		getMenuInflater().inflate(R.menu.favorite, menu);
		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		menu.findItem(R.id.menu_favorite_remove).setVisible(!mCurrentNameList.equals(FavoriteController.DEFAULT_NAME));
		return super.onPrepareOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.menu_favorite_add: {
				addDialog();
				break;
			}

			case R.id.menu_favorite_remove: {
				removeDialog();
				break;
			}
		}
		return super.onOptionsItemSelected(item);
	}

	private void addDialog() {
		final EditTextDialog dialog = new EditTextDialog(this, "", title -> {
			try {
				mController.addList(title);
				mToast.text("Created list '" + title + "'");
				reloadLists();
			} catch (Error e) {
				mToast.text(e.getMessage()).show();
			}
		});
		final EditText et = dialog.getView();
		dialog.setTitle(R.string.popup_favorite_list_create).setOnKeyListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				String value = et.getText().toString();

				if (mController.isAlreadyExists(value)) {
					et.setError(getString(R.string.favorite_list_create_error_already_exists));
					return;
				}

				if (mController.isInvalidName(value)) {
					et.setError(getString(R.string.favorite_list_create_error_invalid_name));
					return;
				}

				et.setError(null);
			}

			@Override
			public void afterTextChanged(Editable s) {}
		});

		dialog.open();
	}

	private void removeDialog() {
		AlertDialog.Builder dialog = new AlertDialog.Builder(this)
				.setTitle(R.string.favorite_list_remove_title)
				.setMessage(getString(R.string.favorite_list_remove_message, mCurrentNameList))
				.setCancelable(false)
				.setPositiveButton(android.R.string.yes, (dlg, buttonId) -> {
					mController.removeList(mCurrentNameList);
					mController.reload();
					reloadLists();
					reloadContent();
				})
				.setNegativeButton(android.R.string.no, (dlg, buttonId) -> {})
				.setIcon(android.R.drawable.ic_dialog_alert);
		dialog.create().show();
	}

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

	@Override
	public void onNothingSelected(AdapterView<?> parent) {}
}
