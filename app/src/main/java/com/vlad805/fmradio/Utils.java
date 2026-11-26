package com.vlad805.fmradio;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.core.os.ConfigurationCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * vlad805 (c) 2019
 */
public class Utils {
	private static final long SHELL_TIMEOUT_MS = 15_000L;
	private static final String[] ROOT_BINARY_PATHS = {
			"/system/bin/su",
			"/system/xbin/su",
			"/sbin/su",
			"/su/bin/su",
			"/vendor/bin/su",
			"/data/local/bin/su",
			"/data/local/xbin/su",
			"/data/local/su"
	};

	/**
	 * Checks for an available su binary without executing it or requesting root access.
	 */
	public static boolean isRootEnvironmentAvailable() {
		final String path = System.getenv("PATH");
		if (path != null) {
			for (final String directory : path.split(":")) {
				if (isExecutableFile(new File(directory, "su"))) {
					return true;
				}
			}
		}

		for (final String binaryPath : ROOT_BINARY_PATHS) {
			if (isExecutableFile(new File(binaryPath))) {
				return true;
			}
		}

		return false;
	}

	private static boolean isExecutableFile(final File file) {
		return file.isFile() && file.canExecute();
	}

	/** Match the packaged ARM executable, including a 32-bit APK on a 64-bit device. */
	public static String determineArch() {
		return android.os.Process.is64Bit() ? "aarch64" : "armv7a";
	}

	/**
	 * Performs a shell command with root privileges.
	 * @param command command to execute
	 * @return exit code
	 */
	public static int runAsRoot(final String command) {
		Process process = null;

		try {
			process = new ProcessBuilder("su").redirectErrorStream(true).start();

			final Process runningProcess = process;

			final Thread outputReader = new Thread(() -> {
				try (
					BufferedReader reader = new BufferedReader(
						new InputStreamReader(
							runningProcess.getInputStream()
						)
					)
				) {
					String line;

					while ((line = reader.readLine()) != null) {
						Log.d("Shell", line);
					}
				} catch (final IOException e) {
					Log.d("Shell", "Shell output stream closed", e);
				}
			}, "ShellOutput");

			outputReader.setDaemon(true);
			outputReader.start();

			try (DataOutputStream os = new DataOutputStream(process.getOutputStream())) {
				os.writeBytes(command + "\n");
				os.writeBytes("exit\n");
				os.flush();
			}

			final long deadline = System.currentTimeMillis() + SHELL_TIMEOUT_MS;

			while (System.currentTimeMillis() < deadline) {
				try {
					final int exitCode = process.exitValue();
					outputReader.join(500L);
					return exitCode;
				} catch (final IllegalThreadStateException ignored) {
					Thread.sleep(50L);
				}
			}

			Log.e("Shell", "Shell command timed out: " + command);
			process.destroy();
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			if (process != null) {
				process.destroy();
			}
			Log.e("Shell", "Shell command interrupted: " + command, e);
		} catch (final IOException | SecurityException e) {
			Log.e("Shell", "Failed to run shell command: " + command, e);
		}
		return -1;
	}

	public static void sleep(final int ms) {
		try {
			Thread.sleep(ms);
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
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
		new com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
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

	@NonNull
	public static String getLanguageCode(@NonNull final Context context) {
		final Locale locale = ConfigurationCompat
				.getLocales(context.getResources().getConfiguration())
				.get(0);
		return (locale != null ? locale : Locale.getDefault())
				.getLanguage()
				.toLowerCase(Locale.US);
	}

	public static void browseUrl(final Context context, @NonNull final String url) {
		final Intent intent = new Intent(Intent.ACTION_VIEW);
		intent.setData(Uri.parse(url));
		context.startActivity(intent);
	}
}
