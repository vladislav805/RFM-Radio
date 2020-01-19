package com.vlad805.fmradio.controller;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.helper.json.JSONFile;
import com.vlad805.fmradio.models.FavoriteFile;
import com.vlad805.fmradio.models.FavoriteStation;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * vlad805 (c) 2020
 */
public class FavoriteController extends JSONFile<FavoriteFile> {
	private List<FavoriteStation> mList;
	private final SharedPreferences mStorage;

	private static final String KEY_CURRENT_LIST = "favorites_list_current";
	private static final String DEFAULT_NAME = "default";

	private static final String KEY_JSON_ITEMS = "items";

	public FavoriteController(Context context) {
		this.mStorage = Utils.getStorage(context);
	}

	/**
	 * Returns name of file of current favorite list
	 * @return filename without extension
	 */
	public String getCurrentFavoriteList() {
		return mStorage.getString(KEY_CURRENT_LIST, DEFAULT_NAME);
	}

	public void setCurrentFavoriteList(String name) {
		try {
			if (!new File(getBaseApplicationDirectory(), getCurrentFavoriteList() + ".json").exists()) {
				Log.d("FC_SCFL", "setCurrentFavoriteList: not found on set");
				return;
			}

			mStorage.edit().putString(KEY_CURRENT_LIST, name).apply();
			load();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}

	}

	public List<String> getFavoriteLists() {
		try {
			File dir = new File(getBaseApplicationDirectory());
			String[] files = dir.list();

			for (int i = 0; i < files.length; ++i) {
				files[i] = files[i].replace(".json", "");
			}

			return Arrays.asList(files);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}

		return new ArrayList<>();
	}

	/**
	 * Load and parse list from file
	 */
	private void load() {
		mList = read().getItems();
	}

	/**
	 * Save local list to file
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
		return super.getDirectory() + "favorites/";
	}

	/**
	 * Name of current favorites list
	 * @return Filename with extension
	 */
	@Override
	public String getFilename() {
		return getCurrentFavoriteList() + ".json";
	}

	/**
	 * Read and parse file
	 * @return Struct
	 */
	@Override
	public FavoriteFile read() {
		try {
			String str = readFile();
			JSONObject obj = new JSONObject(str);
			JSONArray items = obj.getJSONArray(KEY_JSON_ITEMS);
			List<FavoriteStation> fs = new ArrayList<>();

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
	public void write(FavoriteFile data) {
		try {
			List<JSONObject> list = new ArrayList<>();

			for (FavoriteStation station : mList) {
				list.add(station.toJson());
			}

			String str = new JSONObject().put(KEY_JSON_ITEMS, new JSONArray(list)).toString();

			writeFile(str);
		} catch (JSONException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Returns favorite stations list of current list
	 * @return Favorite stations
	 */
	public List<FavoriteStation> getList() {
		if (mList == null) {
			load();
		}

		return mList;
	}

}
