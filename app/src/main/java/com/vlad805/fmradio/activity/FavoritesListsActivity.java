package com.vlad805.fmradio.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.controller.FavoriteController;
import com.vlad805.fmradio.helper.EditTextDialog;
import com.vlad805.fmradio.helper.RecyclerItemClickListener;
import com.vlad805.fmradio.helper.Toast;
import com.vlad805.fmradio.models.FavoriteStation;
import com.vlad805.fmradio.view.OnDragListener;
import com.vlad805.fmradio.view.SimpleItemTouchHelperCallback;
import com.vlad805.fmradio.view.adapter.FavoriteAdapter;

import java.io.FileNotFoundException;
import java.util.List;

public class FavoritesListsActivity extends Activity implements AdapterView.OnItemSelectedListener, OnDragListener {
	private Menu mMenu;
	private String mCurrentNameList;

	private FavoriteController mController;
	private Spinner mSpinner;
	private List<String> mFavoriteListNames;

	private FavoriteAdapter mAdapter;
	private List<FavoriteStation> mStationsList;

	private ItemTouchHelper mItemTouchHelper;

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

		RecyclerView mRecycler = findViewById(R.id.favorite_list_content);
		mRecycler.addOnItemTouchListener(new RecyclerItemClickListener(this, mRecycler, new RecyclerItemClickListener.OnItemClickListener() {
			@Override
			public void onItemClick(View view, int position) {

			}

			@Override
			public void onLongItemClick(View view, int position) {

			}
		}));

		mAdapter = new FavoriteAdapter(mStationsList, this);
		mRecycler.setAdapter(mAdapter);

		reloadLists();
		reloadContent();

		ItemTouchHelper.Callback callback = new SimpleItemTouchHelperCallback(mAdapter);
		mItemTouchHelper = new ItemTouchHelper(callback);
		mItemTouchHelper.attachToRecyclerView(mRecycler);

		// Listen this only after setSelection
		mSpinner.setOnItemSelectedListener(this);
	}

	@Override
	public void onStartDrag(RecyclerView.ViewHolder viewHolder) {
		mItemTouchHelper.startDrag(viewHolder);
	}

	@Override
	public void onEndDrag() {
		mController.save();
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

	@Override
	public void onNothingSelected(AdapterView<?> parent) {}
}
