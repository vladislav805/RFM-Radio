package com.vlad805.fmradio.models;

import com.vlad805.fmradio.helper.json.IJsonable;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * vlad805 (c) 2020
 */
public class FavoriteFile implements IJsonable {
	public List<FavoriteStation> items;

	public FavoriteFile(List<FavoriteStation> list) {
		items = list;
	}

	public List<FavoriteStation> getItems() {
		return items;
	}

	@Override
	public JSONObject toJson() {
		try {
			return new JSONObject().putOpt("items", items);
		} catch (JSONException e) {
			return null;
		}
	}
}
