package com.vlad805.fmradio.service.fm;

/**
 * May contain data to run
 * vlad805 (c) 2020
 */
public final class LaunchBinaryConfig {
	/**
	 * Port number, that used by the native part of the application (driver)
	 * Commands to control and change the parameters of the FM tuner are sent here
	 */
	public final int clientPort;

	/**
	 * The port number that the application must occupy in order to receive events from
	 * the native part of the application, such as changing the frequency, ending
	 * the search for the next station, or updating the RDS.
	 */
	public final int serverPort;

	public LaunchBinaryConfig(final int client, final int server) {
		clientPort = client;
		serverPort = server;
	}
}
