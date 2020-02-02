package com.vlad805.fmradio;

import android.os.Build;
import android.util.Log;

import java.io.DataOutputStream;
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
}
