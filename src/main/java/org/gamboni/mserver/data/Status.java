package org.gamboni.mserver.data;

import org.gamboni.tech.web.js.JS;

@JS
public record Status(
	String nowPlaying,
	PlayState state,
	double position,
	double duration,
	String time) {

	public static final Status STOPPED = new Status(
			"",
			PlayState.STOPPED,
			0,
			0,
			"");
}