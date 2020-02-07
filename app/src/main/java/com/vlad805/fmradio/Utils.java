package com.vlad805.fmradio;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Locale;

/**
 * vlad805 (c) 2019
 */
public class Utils {

	/**
	 *
	 * https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/app/TermuxInstaller.java#L179
	 */
	public static String determineArch() {
		for (String androidArch : Build.SUPPORTED_ABIS) {
			switch (androidArch) {
				case "arm64-v8a":
					return "aarch64";

				case "armeabi-v7a":
					return "armv7a";
			}
		}
		throw new RuntimeException("Unable to determine arch from Build.SUPPORTED_ABIS = " + Arrays.toString(Build.SUPPORTED_ABIS));
	}

	/**
	 * Perform shell command
	 * @param cmd Command
	 * @param su true, if root needed
	 * @return exit code
	 */
	public static int shell(String cmd, boolean su) {
		try {
			Log.d("Shell", cmd);
			String prefix = su ? "su" : "sh";
			Process p = Runtime.getRuntime().exec(prefix);
			DataOutputStream os = new DataOutputStream(p.getOutputStream());
			os.writeBytes(cmd + "\n");
			os.writeBytes("exit\n");
			os.flush();
			return p.waitFor();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return -1;
	}

	public static void sleep(int ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	public static int parseInt(String s) {
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return Integer.MAX_VALUE;
		}
	}

	public static String getMHz(int kHz) {
		return String.format(Locale.ENGLISH, "%5.1f", kHz / 1000.);
	}

	public static String getTimeStringBySeconds(int seconds) {
		double second = Math.floor(seconds % 60);
		double minute = Math.floor(seconds / 60f % 60f);
		long hour = Math.round(Math.floor(seconds / 60f / 60f % 60f));

		return (hour > 0 ? hour + ":" : "") + String.format(Locale.ENGLISH, "%02.0f:%02.0f", minute, second);
	}

	public static void alert(final Context context, final @StringRes int title, final @StringRes int content, final @StringRes int ok) {
		new AlertDialog.Builder(context)
				.setTitle(title)
				.setMessage(content)
				.setCancelable(false)
				.setPositiveButton(ok, (dialog, which) -> dialog.cancel())
				.create()
				.show();
	}

	public interface FetchCallback {
		void onSuccess(final JSONObject result);
		default void onError(final Throwable exception) {
			exception.printStackTrace();
		}
	}

	public static void fetch(final String urlString, FetchCallback callback) {
		try {
			final URL url = new URL(urlString);
			final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("GET");
			connection.setReadTimeout(10000);
			connection.setConnectTimeout(15000);
			connection.setDoOutput(true);
			connection.connect();

			final BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()));
			final StringBuilder sb = new StringBuilder();

			String line;
			while ((line = br.readLine()) != null) {
				sb.append(line).append("\n");
			}
			br.close();

			final JSONObject json = new JSONObject(sb.toString());

			callback.onSuccess(json);
		} catch (IOException | JSONException e) {
			callback.onError(e);
		}
	}

	public static void uiThread(final Runnable run) {
		new Handler(Looper.getMainLooper()).post(run);
	}
}
