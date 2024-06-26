/**
 * 
 */
package org.gamboni.mserver.data;

import com.google.common.collect.ImmutableList;

import java.io.File;

/**
 * @author tendays
 *
 */
public class Item {
	public final File file;
	private final String ext;
	public final String base;
	public final String name;

	public Item(File file) {
		this.file = file;
		
		this.name = file.getName();
		int dot = name.lastIndexOf('.');
		this.ext = name.substring(dot + 1);
		this.base = (dot == -1) ? name : name.substring(0, dot);
	}

	public String toString() {
		return file.getName();
	}

	public boolean isMusic() {
		return ImmutableList.of("3gp", "ogg", "mp3", "mp4", "wma", "mpeg", "mpg").contains(ext);
	}

	public String friendlyName() {
		String result = base.replaceFirst("^[0-9]+\\.", "");
		result = result.replaceAll("[_-]+", " ");
		return result;
	}

	public boolean isDirectory() {
		return file.isDirectory();
	}

	public boolean isAt(File path) {
		return this.file.getPath().startsWith(path.getPath());
	}
}
