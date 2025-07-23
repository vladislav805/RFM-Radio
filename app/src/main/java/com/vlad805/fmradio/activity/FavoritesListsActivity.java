package com.vlad805.fmradio.activity;

import android.Manifest; // <-- Add
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager; // <-- Add
import android.os.Build; // <-- Add
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import androidx.annotation.NonNull; // <-- Add
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat; // <-- Add
import androidx.core.content.ContextCompat; // <-- Add
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.controller.FavoriteController;
import com.vlad805.fmradio.controller.RadioController;
import com.vlad805.fmradio.controller.TunerStatus;
import com.vlad805.fmradio.helper.EditTextDialog;
import com.vlad805.fmradio.helper.ProgressDialog;
import com.vlad805.fmradio.helper.RecyclerItemClickListener;
import com.vlad805.fmradio.helper.Toast;
import com.vlad805.fmradio.models.FavoriteStation;
import com.vlad805.fmradio.view.OnDragListener;
import com.vlad805.fmradio.view.SimpleItemTouchHelperCallback;
import com.vlad805.fmradio.view.adapter.FavoriteAdapter;
import androidx.core.content.ContextCompat; // <-- Add this import


import java.io.FileNotFoundException;
import java.util.ArrayList; // <-- Add if missing
import java.util.List;

public class FavoritesListsActivity extends AppCompatActivity implements AdapterView.OnItemSelectedListener, OnDragListener {

	// --- Permission Request Codes & Strings ---
	private static final int REQUEST_CODE_PERMISSIONS_STORAGE = 201;
	private static final String[] PERMISSIONS_STORAGE; // Initialized statically below

	static {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
			PERMISSIONS_STORAGE = new String[]{
					Manifest.permission.READ_MEDIA_AUDIO // Placeholder, favorites need rework
			};
		} else {
			PERMISSIONS_STORAGE = new String[]{
					Manifest.permission.WRITE_EXTERNAL_STORAGE,
					Manifest.permission.READ_EXTERNAL_STORAGE
			};
		}
	}


	private ProgressDialog mProgress;
	private Menu mMenu;
	private String mCurrentNameList;

	private RadioController mRadioCtl;

	private FavoriteController mController;
	private Spinner mSpinner;
	private List<String> mFavoriteListNames = new ArrayList<>(); // Initialize to avoid nulls

	private FavoriteAdapter mAdapter;
	private List<FavoriteStation> mStationsList = new ArrayList<>(); // Initialize to avoid nulls

	private ItemTouchHelper mItemTouchHelper;

	private Toast mToast;

	private RecyclerView mRecycler; // Make RecyclerView a field for enabling/disabling

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_favorites_lists);

		mRadioCtl = new RadioController(this);
		mToast = Toast.create(this);
		mController = new FavoriteController(this); // OK to initialize

		// --- Permission Check ---
		// We need storage to read/write favorites JSON files
		checkAndRequestStoragePermission();
	}

	private void checkAndRequestStoragePermission() {
		if (hasPermissions(PERMISSIONS_STORAGE)) {
			// Permission granted, proceed with full UI initialization
			initUI();
		} else {
			// Request permission
			ActivityCompat.requestPermissions(this, PERMISSIONS_STORAGE, REQUEST_CODE_PERMISSIONS_STORAGE);
			// Initialize basic UI structure but disable/hide elements needing storage
			initBaseUI(); // Separate method for non-storage dependent UI
			mToast.text("Storage permission needed to manage favorites.").show();
		}
	}

	private boolean hasPermissions(String... permissions) {
		if (permissions != null) {
			for (String permission : permissions) {
				if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
					return false;
				}
			}
		}
		return true;
	}


	// Initialize only UI elements that DON'T immediately require storage access
	private void initBaseUI() {
		final Toolbar toolbar = findViewById(R.id.favorite_list_toolbar);
		mSpinner = findViewById(R.id.favorite_list_lists);
		mRecycler = findViewById(R.id.favorite_list_content); // Assign to field

		setSupportActionBar(toolbar);
		final ActionBar ab = getSupportActionBar();
		if (ab != null) {
			ab.setDisplayHomeAsUpEnabled(true);
		}

		// Disable elements until permission granted
		if(mSpinner != null) mSpinner.setEnabled(false);
		if(mRecycler != null) {
			mRecycler.setVisibility(View.GONE); // Hide list view
		}

	}

	// Initialize the rest of the UI, assuming permissions are granted
	private void initUI() {
		initBaseUI(); // Setup toolbar etc.

		// Enable elements now
		if(mSpinner != null) mSpinner.setEnabled(true);
		if(mRecycler != null) mRecycler.setVisibility(View.VISIBLE); // Show list view

		// Now safe to access FavoriteController for reading lists
		try {
			mFavoriteListNames = mController.getFavoriteLists();

			// Setup RecyclerView
			if (mRecycler != null) {
				mRecycler.addOnItemTouchListener(new RecyclerItemClickListener(this, mRecycler, new RecyclerItemClickListener.OnItemClickListener() {
					@Override
					public void onItemClick(final View view, final int position) {
						if (mStationsList != null && position < mStationsList.size()) {
							final FavoriteStation station = mStationsList.get(position);
							mRadioCtl.setFrequency(station.getFrequency());
						}
					}

					@Override
					public void onLongItemClick(final View view, int position) {
						// TODO: open menu here if needed
					}
				}));

				// Initialize adapter with an empty list first, then load data
				mAdapter = new FavoriteAdapter(mStationsList, this); // Use initialized empty list
				mRecycler.setAdapter(mAdapter);

				// Item Touch Helper for drag/drop
				final ItemTouchHelper.Callback callback = new SimpleItemTouchHelperCallback(mAdapter);
				mItemTouchHelper = new ItemTouchHelper(callback);
				mItemTouchHelper.attachToRecyclerView(mRecycler);
			}


			// Load data
			reloadLists();
			reloadContent();

			// Listen for spinner changes only after initial setup
			if (mSpinner != null) mSpinner.setOnItemSelectedListener(this);

		} catch (Exception e) {
			// This might happen if storage access fails unexpectedly
			mToast.text("Error initializing favorites data: " + e.getMessage()).show();
			if(mSpinner != null) mSpinner.setEnabled(false);
			if(mRecycler != null) mRecycler.setVisibility(View.GONE);
		}
	}

	// --- Permission Result Handler ---
	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);

		if (requestCode == REQUEST_CODE_PERMISSIONS_STORAGE) {
			if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				// Permission granted, initialize the full UI now
				mToast.text("Storage permission granted.").show();
				initUI(); // Call the full initialization method
			} else {
				// Permission denied
				mToast.text("Storage permission denied. Cannot manage favorites. Exiting.").show();
				// Keep UI disabled or finish the activity?
				finish(); // Example: Exit if storage is essential
			}
		}
	}

	// --- Methods potentially needing permission check before action ---

	@Override
	public void onEndDrag() {
		if (!hasPermissions(PERMISSIONS_STORAGE)) {
			mToast.text("Storage permission required to save changes.").show();
			// Re-request or prevent save? For now, just show toast.
			// ActivityCompat.requestPermissions(this, PERMISSIONS_STORAGE, REQUEST_CODE_PERMISSIONS_STORAGE);
			return;
		}
		mController.save();
	}

	private void addDialog() {
		// Check permission before showing dialog that leads to adding a list
		if (!hasPermissions(PERMISSIONS_STORAGE)) {
			mToast.text("Storage permission required to add lists.").show();
			ActivityCompat.requestPermissions(this, PERMISSIONS_STORAGE, REQUEST_CODE_PERMISSIONS_STORAGE);
			return;
		}

		final EditTextDialog dialog = new EditTextDialog(this, "", title -> {
			try {
				mController.addList(title);
				mToast.text("Created list '" + title + "'").show(); // Use show()
				reloadLists();
			} catch (Error e) { // Catch specific error if defined, or Exception
				mToast.text(e.getMessage()).show();
			} catch (Exception e) { // Catch potential file IO exceptions
				mToast.text("Error creating list: " + e.getMessage()).show();
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
		if (!hasPermissions(PERMISSIONS_STORAGE)) {
			mToast.text("Storage permission required to remove lists.").show();
			ActivityCompat.requestPermissions(this, PERMISSIONS_STORAGE, REQUEST_CODE_PERMISSIONS_STORAGE);
			return;
		}
		// Check if a list is selected and it's not the default one
		if (mCurrentNameList == null || mCurrentNameList.equals(FavoriteController.DEFAULT_NAME)) {
			mToast.text("Cannot remove the default list.").show();
			return;
		}

		final AlertDialog.Builder dialog = new AlertDialog.Builder(this)
				.setTitle(R.string.favorite_list_remove_title)
				.setMessage(getString(R.string.favorite_list_remove_message, mCurrentNameList))
				.setCancelable(false)
				.setPositiveButton(android.R.string.yes, (dlg, buttonId) -> {
					try {
						boolean removed = mController.removeList(mCurrentNameList);
						if(removed) {
							mController.reload(); // Reload internal state after removal
							reloadLists();    // Update spinner
							reloadContent();  // Update RecyclerView
							mToast.text("List removed.").show();
						} else {
							mToast.text("Failed to remove list.").show();
						}
					} catch (Exception e) {
						mToast.text("Error removing list: " + e.getMessage()).show();
					}
				})
				.setNegativeButton(android.R.string.no, (dlg, buttonId) -> {})
				.setIcon(android.R.drawable.ic_dialog_alert);
		dialog.create().show();
	}


	// --- Other existing methods (initUI needs careful review) ---

	// reloadLists and reloadContent now assume permission is granted,
	// as they are called from initUI or after actions that should have checked.

	private void reloadLists() {
		if (!hasPermissions(PERMISSIONS_STORAGE)) return; // Safety check
		try {
			mFavoriteListNames = mController.getFavoriteLists();
			mCurrentNameList = mController.getCurrentFavoriteList();
			final int position = mFavoriteListNames.indexOf(mCurrentNameList);

			final ArrayAdapter<?> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, mFavoriteListNames.toArray(new String[0]));

			if (mSpinner != null) {
				mSpinner.setAdapter(adapter);
				// Ensure position is valid before setting selection
				if (position >= 0 && position < mFavoriteListNames.size()) {
					mSpinner.setSelection(position);
				} else if (!mFavoriteListNames.isEmpty()) {
					mSpinner.setSelection(0); // Select first if current not found
					// Optionally try setting default
					try { mController.setCurrentFavoriteList(mFavoriteListNames.get(0)); } catch (Exception ignored) {}
				}
			}
		} catch(Exception e) {
			mToast.text("Error loading favorite lists: " + e.getMessage()).show();
		}
	}


	private void reloadContent() {
		if (!hasPermissions(PERMISSIONS_STORAGE)) return; // Safety check

		// Ensure adapter exists
		if (mAdapter == null) {
			mAdapter = new FavoriteAdapter(new ArrayList<>(), this); // Create with empty list
			if (mRecycler != null) mRecycler.setAdapter(mAdapter);
		}

		try {
			// Clear the existing list in the adapter before getting the new one
			if (mStationsList != null) {
				mStationsList.clear();
			} else {
				mStationsList = new ArrayList<>();
			}

			// Get the new list and add items to the existing list object
			List<FavoriteStation> newStations = mController.getStationsInCurrentList();
			if (newStations != null) {
				mStationsList.addAll(newStations);
			}

			// Notify adapter
			mAdapter.setDataset(mStationsList); // Ensure adapter uses the correct list instance
			mAdapter.notifyDataSetChanged();

			// Update menu visibility based on the current list name
			if (mMenu != null) {
				MenuItem removeItem = mMenu.findItem(R.id.menu_favorite_remove);
				if (removeItem != null) {
					removeItem.setVisible(!FavoriteController.DEFAULT_NAME.equals(mCurrentNameList));
				}
			}
		} catch (Exception e) {
			mToast.text("Error loading stations: " + e.getMessage()).show();
		}
	}


	@Override
	public void onItemSelected(final AdapterView<?> parent, final View view, final int position, final long id) {
		if (!hasPermissions(PERMISSIONS_STORAGE)) {
			// If permission was revoked, prevent changing list
			mToast.text("Storage permission needed to change list.").show();
			ActivityCompat.requestPermissions(this, PERMISSIONS_STORAGE, REQUEST_CODE_PERMISSIONS_STORAGE);
			// Revert spinner selection?
			int previousPosition = mFavoriteListNames.indexOf(mCurrentNameList);
			if (previousPosition >= 0) parent.setSelection(previousPosition);
			return;
		}

		String item = (String) parent.getItemAtPosition(position);
		if (item == null || item.equals(mCurrentNameList)) {
			return; // No change
		}

		try {
			mController.setCurrentFavoriteList(item);
			mCurrentNameList = item; // Update local cache of current name
			reloadContent(); // Reload stations for the new list

			// Notify MainActivity that favorites might have changed (for seek by favorite)
			final Intent intent = new Intent().putExtra("changed", true);
			setResult(Activity.RESULT_OK, intent);
			sendBroadcast(new Intent(C.Event.FAVORITE_LIST_CHANGED));

		} catch (final FileNotFoundException e) {
			mToast.text("Not found this list").show();
			// Revert selection?
			int previousPosition = mFavoriteListNames.indexOf(mCurrentNameList); // Use previous name
			if (previousPosition >= 0) parent.setSelection(previousPosition);
		} catch (Exception e) {
			mToast.text("Error changing list: " + e.getMessage()).show();
			int previousPosition = mFavoriteListNames.indexOf(mCurrentNameList);
			if (previousPosition >= 0) parent.setSelection(previousPosition);
		}
	}

	// ... (rest of the methods: onCreateOptionsMenu, onPrepareOptionsMenu, onOptionsItemSelected - searchDialog check) ...
	@Override
	public boolean onCreateOptionsMenu(final Menu menu) {
		super.onCreateOptionsMenu(menu);
		mMenu = menu;
		getMenuInflater().inflate(R.menu.favorite, menu);
		// Initial visibility depends on whether permission is granted and data loaded
		if (hasPermissions(PERMISSIONS_STORAGE) && mCurrentNameList != null) {
			MenuItem removeItem = menu.findItem(R.id.menu_favorite_remove);
			if (removeItem != null) {
				removeItem.setVisible(!mCurrentNameList.equals(FavoriteController.DEFAULT_NAME));
			}
		} else {
			MenuItem removeItem = menu.findItem(R.id.menu_favorite_remove);
			if (removeItem != null) removeItem.setVisible(false);
		}
		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		// Check permission and list name again
		if (hasPermissions(PERMISSIONS_STORAGE) && mCurrentNameList != null) {
			MenuItem removeItem = menu.findItem(R.id.menu_favorite_remove);
			if (removeItem != null) {
				removeItem.setVisible(!mCurrentNameList.equals(FavoriteController.DEFAULT_NAME));
			}
		} else {
			MenuItem removeItem = menu.findItem(R.id.menu_favorite_remove);
			if (removeItem != null) removeItem.setVisible(false);
		}
		return super.onPrepareOptionsMenu(menu);
	}


	@SuppressLint("NonConstantResourceId")
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		final int itemId = item.getItemId(); // Use final variable
		if (itemId == R.id.menu_favorite_add) {
			addDialog(); // Check inside addDialog
		} else if (itemId == R.id.menu_favorite_remove) {
			removeDialog(); // Check inside removeDialog
		} else if (itemId == R.id.menu_favorite_search) {
			searchDialog(); // Needs RECORD_AUDIO check
		} else if (itemId == android.R.id.home) {
			finish();
		} else {
			return super.onOptionsItemSelected(item);
		}
		return true;
	}


	private void searchDialog() {
		// 1) RECORD_AUDIO check
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
				!= PackageManager.PERMISSION_GRANTED) {
			mToast.text("Audio permission required to check tuner state for search.").show();
			return; // skip if no audio permission
		}

		// 2) check tuner state asynchronously
		mRadioCtl.getCurrentState(state -> {
			if (state.getStatus() != TunerStatus.ENABLED) {
				mToast.text(R.string.favorite_list_search_error_tuner_not_enabled).show();
				return;
			}

			// 3) STORAGE check
			if (!hasPermissions(PERMISSIONS_STORAGE)) {
				mToast.text("Storage permission required to save search results.").show();
				ActivityCompat.requestPermissions(
						this,
						PERMISSIONS_STORAGE,
						REQUEST_CODE_PERMISSIONS_STORAGE
				);
				return;
			}

			// 4) confirmation dialog
			new AlertDialog.Builder(this)
					.setTitle(R.string.favorite_list_search_confirm_title)
					.setMessage(R.string.favorite_list_search_confirm_message)
					.setIcon(android.R.drawable.ic_dialog_alert)
					.setCancelable(false)
					.setPositiveButton(R.string.favorite_list_search_confirm_continue,
							(dlg, which) -> searchStart())
					.setNegativeButton(R.string.favorite_list_search_confirm_discard, null)
					.show();
		});
	}

	// ... (rest of methods: searchStart, mSearchDone, onNothingSelected) ...
	private void searchStart() {
		mRadioCtl.hwSearch();
		mProgress = ProgressDialog.create(this).text(R.string.favorite_list_search_progress).show();
		// Original line:
		// registerReceiver(mSearchDone, new IntentFilter(C.Event.HW_SEARCH_DONE));

		// New line using ContextCompat:
		ContextCompat.registerReceiver(this, mSearchDone, new IntentFilter(C.Event.HW_SEARCH_DONE), ContextCompat.RECEIVER_NOT_EXPORTED);
	}

	private final BroadcastReceiver mSearchDone = new BroadcastReceiver() {
		@Override
		public void onReceive(final Context context, final Intent intent) {
			if (intent == null || intent.getAction() == null || !C.Event.HW_SEARCH_DONE.equals(intent.getAction())) {
				return;
			}

			unregisterReceiver(mSearchDone);

			if (mProgress != null) {
				mProgress.hide();
				mProgress = null; // Clean up progress dialog
			}


			// Check permission again before writing results
			if (!hasPermissions(PERMISSIONS_STORAGE)) {
				mToast.text("Storage permission denied. Cannot save search results.").show();
				return;
			}


			final int[] frequencies = intent.getIntArrayExtra(C.Key.STATION_LIST);
			if (frequencies == null || frequencies.length == 0) {
				mToast.text("No stations found.").show();
				return; // Don't clear list if nothing found
			}


			// Ensure mStationsList is initialized
			if (mStationsList == null) {
				mStationsList = new ArrayList<>();
			}

			// Use the controller to get/clear the list, then save
			try {
				List<FavoriteStation> stations = mController.getStationsInCurrentList(); // Get the list managed by controller
				stations.clear(); // Clear it

				for (final int kHz : frequencies) {
					stations.add(new FavoriteStation(kHz, Utils.getMHz(kHz))); // Add new stations
				}

				mController.save(); // Save the modified list

				// Update UI
				reloadLists(); // Might not be needed unless list name changed
				reloadContent(); // Update recycler view
				mToast.text("Found " + frequencies.length + " stations.").show();

			} catch (Exception e) {
				mToast.text("Error saving search results: " + e.getMessage()).show();
			}
		}
	};

	@Override
	public void onNothingSelected(AdapterView<?> parent) {}

	// Need onStartDrag implementation from OnDragListener
	@Override
	public void onStartDrag(final RecyclerView.ViewHolder viewHolder) {
		if (mItemTouchHelper != null) {
			mItemTouchHelper.startDrag(viewHolder);
		}
	}


} // End of FavoritesListsActivity class