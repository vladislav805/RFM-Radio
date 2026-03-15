package com.vlad805.fmradio.helper.json;

import android.content.Context;
import android.util.Log;

import java.io.*;

/**
 * vlad805 (c) 2020
 */
public abstract class JSONFile<T extends IJsonable> {
	private final Context mContext;

	protected JSONFile(final Context context) {
		mContext = context.getApplicationContext();
	}

	/**
	 * Returns (and create recursively, if it not exists) path to application directory
	 * @return Path to application directory
	 */
	protected final String getBaseApplicationDirectory() {
		final File externalDir = mContext.getExternalFilesDir(null);
		if (externalDir == null) {
			throw new IllegalStateException("External files directory is not available");
		}

		File dir = externalDir;

		if (!dir.exists()) {
			if (!dir.mkdirs()) {
				Log.e("JSONFile<>", "Can not create application directory");
			}
		}

		return dir.getAbsolutePath();
	}

	/**
	 * Returns name of JSON file
	 * @return Name file with extension
	 */
	public abstract String getPath();

	/**
	 * Returns full path to file
	 * @return Path
	 */
	protected String getFullPath() {
		return getBaseApplicationDirectory() + File.separator + getPath();
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
		final File parentDir = file.getParentFile();
		if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
			Log.e("JSONFile<>", "Can not create parent directory for " + file.getAbsolutePath());
			return;
		}
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
