package com.vlad805.fmradio.helper.json;

import android.os.Environment;
import android.util.Log;

import java.io.*;

/**
 * vlad805 (c) 2020
 */
public abstract class JSONFile<T extends IJsonable> {

	/**
	 * Returns (and create recursively, if it not exists) path to application directory
	 * @return Path to application directory
	 */
	protected final String getBaseApplicationDirectory() {
		File dir = new File(Environment.getExternalStorageDirectory() + getDirectory());

		if (!dir.exists()) {
			if (!dir.mkdirs()) {
				Log.e("JSONFile<>", "Can not create application directory");
			}
		}

		return dir.getAbsolutePath();
	}

	/**
	 * Returns directory, relative from home directory
	 * @return Path
	 */
	protected String getDirectory() {
		return "/RFM/";
	}

	/**
	 * Returns name of JSON file
	 * @return Name file with extension
	 */
	public abstract String getFilename();

	/**
	 * Returns full path to file
	 * @return Path
	 */
	protected String getFullPath() {
		return getBaseApplicationDirectory() + getFilename();
	}

	/**
	 * Reading file to string
	 * @return Content of file in string
	 */
	protected String readFile() {
		String path = getFullPath();
		File file = new File(path);

		if (!file.exists()) {
			String empty = "{}";
			writeFile(empty);
			return empty;
		}

		StringBuilder text = new StringBuilder();

		try (BufferedReader br = new BufferedReader(new FileReader(file))) {
			String line;

			while ((line = br.readLine()) != null) {
				text.append(line);
				text.append('\n');
			}

			return text.toString();
		} catch (IOException e) {
			return "";
		}
	}

	/**
	 * Save file with data content
	 * @param data Content of file
	 */
	protected void writeFile(String data) {
		File file = new File(getFullPath());
		try (FileOutputStream stream = new FileOutputStream(file)) {
			stream.write(data.getBytes());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Parsing contents to models
	 * @return Model
	 */
	public abstract T read();

	/**
	 * Serialize model to string and write it to file
	 * @param data Model
	 */
	public abstract void write(T data);
}
