package com.vlad805.fmradio.models;

/**
 * vlad805 (c) 2020
 */
public class FavoriteStation {
	private int frequency;
	private String title;

	public FavoriteStation(int frequency, String title) {
		this.frequency = frequency;
		this.title = title;
	}

	public FavoriteStation(int frequency) {
		this(frequency, "");
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
}
