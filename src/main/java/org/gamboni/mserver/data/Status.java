package org.gamboni.mserver.data;

import java.io.File;

public record Status(
	File nowPlaying,
	PlayState state,
	double position,
	double duration,
	String time) {

	public static final Status STOPPED = new Status(
			null, // use Optional?
			PlayState.STOPPED,
			0,
			0,
			"");
}