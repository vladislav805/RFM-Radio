package com.vlad805.fmradio.controller;

import android.content.Context;
import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.helper.json.JSONFile;
import com.vlad805.fmradio.models.FavoriteFile;
import com.vlad805.fmradio.models.FavoriteStation;
import net.grandcentrix.tray.AppPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * vlad805 (c) 2020
 */
public class FavoriteController extends JSONFile<FavoriteFile> {
	private List<FavoriteStation> mList;
	private final AppPreferences mStorage;

	private static final String KEY_CURRENT_LIST = "favorites_list_current";
	private static final String KEY_JSON_ITEMS = "items";
	public static final String DEFAULT_NAME = "default";

	private static final String JSON_EXT = ".json";

	public FavoriteController(final Context context) {
		this.mStorage = Storage.getInstance(context);
	}

	/**
	 * Returns all lists
	 * @return Names of lists
	 */
	public List<String> getFavoriteLists() {
		final File dir = new File(getBaseApplicationDirectory());
		final String[] files = dir.list();

		for (int i = 0; i < files.length; ++i) {
			files[i] = files[i].replace(JSON_EXT, "");
		}

		Arrays.sort(files);

		return Arrays.asList(files);
	}

	/**
	 * Returns name of file of current favorite list
	 * @return filename without extension
	 */
	public String getCurrentFavoriteList() {
		return mStorage.getString(KEY_CURRENT_LIST, DEFAULT_NAME);
	}

	/**
	 * Change current favorite list by name
	 * @param name Name of favorite list
	 */
	public void setCurrentFavoriteList(final String name) throws FileNotFoundException {
		// app                     stat     name   .json
		// /storage/emulated/0/RFM/stations/default.json
		final File file = new File(getBaseApplicationDirectory(), name + JSON_EXT);
		if (!file.exists()) {
			throw new FileNotFoundException("setCurrentFavoriteList: not found list with name '" + name + "'; full path = " + file.getAbsolutePath());
		}

		mStorage.put(KEY_CURRENT_LIST, name);
		reload();
	}

	/**+
	 * Returns favorite stations in list of current list
	 * @return Stations in list
	 */
	public List<FavoriteStation> getStationsInCurrentList() {
		if (mList == null) {
			reload();
		}

		return mList;
	}

	/**
	 * Check name for validity
	 * @param name Name of list
	 * @return True if invalid
	 */
	public boolean isInvalidName(final String name) {
		return !name.matches("[A-Za-z0-9_-]+");
	}

	/**
	 * Check name for existing
	 * @param name Name of list
	 * @return True, if already exists
	 */
	public boolean isAlreadyExists(final String name) {
		return new File(getDirectory(), name + JSON_EXT).exists();
	}

	/**
	 * Create new empty list
	 * @param name Name of list
	 * @return True, if created successfully
	 * @throws Error if name is invalid or list with same name already exists
	 */
	public boolean addList(final String name) {
		if (isInvalidName(name)) {
			throw new Error("Invalid name");
		}

		if (isAlreadyExists(name)) {
			throw new Error("List with this name already exists");
		}

		final File file = new File(getBaseApplicationDirectory(), name + JSON_EXT);

		try (final FileOutputStream stream = new FileOutputStream(file)) {
			stream.write("{\"items\":[]}".getBytes());
		} catch (IOException e) {
			e.printStackTrace();
		}

		return true;
	}

	/**
	 * Remove favorite list
	 * @param name Name of list to remove
	 * @return True, if successfully
	 */
	public boolean removeList(final String name) {
		if (name.equals(DEFAULT_NAME)) {
			return false;
		}

		final String path = getFullPath();
		if (getCurrentFavoriteList().equals(name)) {
			try {
				setCurrentFavoriteList(DEFAULT_NAME);
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			}
		}

		return new File(path).delete();
	}

	/**
	 * Load and parse favorite list from file (current list)
	 */
	public void reload() {
		mList = read().getItems();
	}

	/**
	 * Save local list to file (current list)
	 */
	public void save() {
		write(new FavoriteFile(mList));
	}

	/**
	 * Override directory path to favorites files
	 * @return Path relative from base app dir
	 */
	@Override
	protected String getDirectory() {
		//                        /RFM/
		return super.getDirectory() + "favorites/";
	}

	/**
	 * Name of current favorites list
	 * @return Filename with extension
	 */
	@Override
	public String getFilename() {
		return getCurrentFavoriteList() + JSON_EXT;
	}

	/**
	 * Read and parse file of list
	 * @return Struct
	 */
	@Override
	public FavoriteFile read() {
		try {
			final String str = readFile();
			final JSONObject obj = new JSONObject(str);
			final JSONArray items = obj.getJSONArray(KEY_JSON_ITEMS);
			final List<FavoriteStation> fs = new ArrayList<>();

			for (int i = 0; i < items.length(); ++i) {
				fs.add(new FavoriteStation(items.optJSONObject(i)));
			}

			return new FavoriteFile(fs);
		} catch (JSONException e) {
			return new FavoriteFile(new ArrayList<>());
		}
	}

	/**
	 * Write data to file
	 * @param data Fresh favorite list
	 */
	@Override
	public void write(final FavoriteFile data) {
		try {
			final List<JSONObject> list = new ArrayList<>();

			for (final FavoriteStation station : mList) {
				list.add(station.toJson());
			}

			final String str = new JSONObject().put(KEY_JSON_ITEMS, new JSONArray(list)).toString();

			writeFile(str);
		} catch (JSONException e) {
			e.printStackTrace();
		}
	}
}
