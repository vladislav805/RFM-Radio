package com.vlad805.fmradio.fm;

import com.vlad805.fmradio.BuildConfig;

/**
 * vlad805 (c) 2020
 */
public abstract class LaunchConfig {

	/**
	 * Returns No antenna
	 * @return No antenna
	 */
	public int getAntenna() {
		return 0;
	}

	/**
	 * Returns the client binary endpoint port where commands are sent
	 * @return Port
	 */
	public int getClientPort() {
		return 2112;
	}

	/**
	 * Server on application, that receive events as RDS
	 * @return Port
	 */
	public int getServerPort() {
		return 2113;
	}

	/**
	 * Returns true, if RDS enabled
	 */
	public boolean getRdsEnable() {
		return true;
	}

	public boolean getLogsEnabled() {
		return BuildConfig.DEBUG;
	}

}
