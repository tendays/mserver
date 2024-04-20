package org.gamboni.mserver;

public class Status {
	final String nowPlaying;
	final boolean paused;
	final double position;
	final double length;
	final String time;
	
	public Status(String nowPlaying, boolean paused, double position, double length, String time) {
		this.nowPlaying = nowPlaying;
		this.paused = paused;
		this.position = position;
		this.length = length;
		this.time = time;
	}
}