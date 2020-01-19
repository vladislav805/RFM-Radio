package com.vlad805.fmradio.models;

import com.vlad805.fmradio.helper.json.IJsonable;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * vlad805 (c) 2020
 */
public class FavoriteStation implements IJsonable {
	private int frequency;
	private String title;

	public static final String KEY_FREQUENCY = "frequency";
	public static final String KEY_TITLE = "title";

	public FavoriteStation(int frequency, String title) {
		this.frequency = frequency;
		this.title = title;
	}

	public FavoriteStation(int frequency) {
		this(frequency, "");
	}

	public FavoriteStation(JSONObject json) {
		this(json.optInt(KEY_FREQUENCY, 108100), json.optString(KEY_TITLE, ""));
	}

	public int getFrequency() {
		return frequency;
	}

	public String getTitle() {
		return title;
	}

	public void setFrequency(int frequency) {
		this.frequency = frequency;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	@Override
	public JSONObject toJson() {
		try {
			return new JSONObject().put(KEY_FREQUENCY, frequency).put(KEY_TITLE, title);
		} catch (JSONException e) {
			return null;
		}
	}


}
