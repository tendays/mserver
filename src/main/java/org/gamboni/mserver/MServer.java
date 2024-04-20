/**
 * 
 */
package org.gamboni.mserver;

import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;
import org.gamboni.mserver.data.Item;

import com.google.common.io.ByteStreams;

import org.gamboni.mserver.ui.DirectoryPage;
import org.gamboni.mserver.ui.Script;
import org.gamboni.mserver.ui.Style;
import spark.Response;
import spark.Spark;

import static java.util.Arrays.asList;

/**
 * @author tendays
 *
 */
@Slf4j
public class MServer {

	public static void main(String[] args) {
		if (args.length < 1) {
			System.err.println("Usage: MServer <path> [extra mplayer parameters]");
			System.exit(255);
		}
		new MServer(new File(args[0]), asList(args).subList(1, args.length)).run();
	}

	private final MServerController controller;
	private final Style style;
	private final Script script;
	private final File folder;

	private final DirectoryPage page;
	
	public MServer(File folder, List<String> extraParams) {
		this.folder = folder;
		Spark.port(4568);

		Spark.exception(Exception.class, (ex, req, res) -> log.error("Uncaught Exception", ex));
		
		this.controller = new MServerController(this, folder, extraParams);
		this.script = new Script(controller);
		this.style = new Style();
		this.page = new DirectoryPage(controller, style, script);
		script.setPage(page);
	}
	
	private void run() {
		Spark.get("/*", (req, res) -> {
			File childFolder = (req.splat().length == 0) ? folder : new File(folder, req.splat()[0]);
			PathOrError<String> poe = relativePath(childFolder);
			if (poe.error != null) {
				return notFound(res, poe.error);
			}
			String relativePath = poe.path +
					(poe.path.isEmpty() ? "" : File.separator);
			
			if (childFolder.isFile()) {
				ByteStreams.copy(new FileInputStream(childFolder), res.raw().getOutputStream());
				return null;
			}
			
			File[] files = childFolder.listFiles();
			if (files == null) {
				return notFound(res, "Could not list files under "+ childFolder);
			}
			res.header("Content-Security-Policy", "default-src * 'unsafe-inline' 'unsafe-eval'; script-src * 'unsafe-inline' 'unsafe-eval'; connect-src * 'unsafe-inline'; img-src * data: blob: 'unsafe-inline'; frame-src *; style-src * 'unsafe-inline';");
			return page.render(
					relativePath,
					Stream.of(files)
					.sorted(Comparator.comparing(file -> file.getName().toLowerCase()))
							.map(file -> new Item(this, file))
							.toList());
		});
	}
	
	public PathOrError<String> relativePath(File file) {
		if (!file.getPath().startsWith(folder.getPath())) {
			return PathOrError.error("Requested "+ file.getPath() +" does not start from root "+ folder.getPath());
		} else {
			return PathOrError.of(file.getPath().substring(folder.getPath().length()));
		}
	}
	
	public static class PathOrError<T> {
		public final T path;
		public final String error;
		private PathOrError(T path, String error) {
			this.path = path;
			this.error = error;
		}
		public static <T> PathOrError<T> of(T path) {
			return new PathOrError<>(path, null);
		}
		public static <T> PathOrError<T> error(String error) {
			return new PathOrError<>(null, error);
		}
	}

	private String notFound(Response res, String error) {
		log.error(error);
		res.status(404);
		return "not found";
	}
}
