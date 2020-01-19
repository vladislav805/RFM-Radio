package com.vlad805.fmradio.helper.json;

import android.os.Environment;

import java.io.*;

/**
 * vlad805 (c) 2020
 */
public abstract class JSONFile<T extends IJsonable> {

	protected String getBaseApplicationDirectory() throws FileNotFoundException {
		File dir = new File(Environment.getExternalStorageDirectory() + getDirectory());

		if (!dir.exists()) {
			if (!dir.mkdirs()) {
				throw new FileNotFoundException();
			}
		}

		return dir.getAbsolutePath();
	}

	protected String getDirectory() {
		return "/RFM/";
	}

	public abstract String getFilename();

	protected String getFullPath() throws FileNotFoundException {
		return getBaseApplicationDirectory() + getFilename();
	}

	protected String readFile() {
		try {
			String path = getFullPath();
			File file = new File(path);

			if (!file.exists()) {
				writeFile("{}");
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
		} catch (FileNotFoundException e) {
			return "{}";
		}
	}

	protected void writeFile(String data) {
		try {
			File file = new File(getFullPath());
			try (FileOutputStream stream = new FileOutputStream(file)) {
				stream.write(data.getBytes());
			} catch (IOException e) {
				e.printStackTrace();
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
	}

	public abstract T read();
	public abstract void write(T data);

}
