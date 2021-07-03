package com.vlad805.fmradio;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyManager;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
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
		for (final String androidArch : Build.SUPPORTED_ABIS) {
			switch (androidArch) {
				case "arm64-v8a": {
					return "aarch64";
				}

				case "armeabi-v7a": {
					return "armv7a";
				}
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
	public static int shell(final String cmd, final boolean su) {
		try {
			Log.d("Shell", cmd);
			final String prefix = su ? "su" : "sh";
			final Process p = Runtime.getRuntime().exec(prefix);
			final DataOutputStream os = new DataOutputStream(p.getOutputStream());
			os.writeBytes(cmd + "\n");
			os.writeBytes("exit\n");
			os.flush();
			return p.waitFor();
		} catch (final Exception e) {
			e.printStackTrace();
		}
		return -1;
	}

	public static void sleep(final int ms) {
		try {
			Thread.sleep(ms);
		} catch (final InterruptedException e) {
			e.printStackTrace();
		}
	}

	public static int parseInt(final String s) {
		try {
			return Integer.parseInt(s);
		} catch (final NumberFormatException e) {
			return Integer.MAX_VALUE;
		}
	}

	public static String getMHz(final int kHz) {
		return getMHz(kHz, 1);
	}

	public static String getMHz(final int kHz, final int precision) {
		return String.format(Locale.ENGLISH, "%5." + precision + "f", kHz / 1000.);
	}

	public static String getTimeStringBySeconds(final long seconds) {
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

	public static boolean isInternetAvailable() {
		try {
			final InetAddress address = InetAddress.getByName("www.google.com");

			//noinspection EqualsBetweenInconvertibleTypes
			return address != null && !address.equals("");
		} catch (final UnknownHostException e) {
			// Log error
		}
		return false;
	}

	public static void fetch(final String urlString, final FetchCallback callback) {
		// Create new thread, cause requests to network not allowed in main thread (UI thread)
		new Thread(() -> {
			// Check if Internet available
			if (!isInternetAvailable()) {
				uiThread(() -> callback.onError(new Error("No Internet connection")));
				return;
			}

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

				// Return result to main/UI thread
				uiThread(() -> callback.onSuccess(json));
			} catch (final IOException | JSONException e) {
				// Return error to main/UI thread
				uiThread(() -> callback.onError(e));
			}
		}).start();
	}

	public static void uiThread(final Runnable run) {
		new Handler(Looper.getMainLooper()).post(run);
	}

	public static String getCountryISO(final Context context) {
		try {
			final TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
			final String simCountry = tm.getSimCountryIso();

			// SIM country code is available
			if (simCountry != null && simCountry.length() == 2) {
				return simCountry.toLowerCase(Locale.US);
			} else if (tm.getPhoneType() != TelephonyManager.PHONE_TYPE_CDMA) { // device is not 3G (would be unreliable)
				String networkCountry = tm.getNetworkCountryIso();
				if (networkCountry != null && networkCountry.length() == 2) { // network country code is available
					return networkCountry.toLowerCase(Locale.US);
				}
			}
		} catch (Exception ignored) {
		}

		return null;
	}

	public static void browseUrl(final Context context, @NonNull final String url) {
		final Intent intent = new Intent(Intent.ACTION_VIEW);
		intent.setData(Uri.parse(url));
		context.startActivity(intent);
	}
}
