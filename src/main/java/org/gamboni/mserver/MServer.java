/**
 * 
 */
package org.gamboni.mserver;

import lombok.extern.slf4j.Slf4j;
import org.gamboni.mserver.data.Item;
import org.gamboni.mserver.tech.Mapping;
import org.gamboni.mserver.ui.DirectoryPage;
import org.gamboni.mserver.ui.Script;
import org.gamboni.mserver.ui.Style;
import spark.Response;
import spark.Spark;

import java.io.File;
import java.io.FileInputStream;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

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
	private final File root;
	private final DirectoryPage page;
	private final Mapping mapping;
	
	public MServer(File root, List<String> extraParams) {
		this.root = root;
		this.mapping = new Mapping(root);

		Spark.port(4568);

		Spark.exception(Exception.class, (ex, req, res) -> log.error("Uncaught Exception", ex));

		// WARN: web socket creation must be done before any route, so this must come first
		var socketHandler = new MServerSocket(mapping);
		this.controller = new MServerController(mapping, socketHandler, root, extraParams);

		var style = new Style();
		var script = new Script(style, controller, socketHandler);
		this.page = new DirectoryPage(controller, mapping, style, script);
	}
	
	private void run() {
		Spark.redirect.get("/", "/browse/");

		Spark.get("/browse/*", (req, res) -> {
			File childFolder = (req.splat().length == 0) ? root : mapping.pathToFile(req.splat()[0]);

			if (childFolder.isFile()) {
				try (var in = new FileInputStream(childFolder)) {
					in.transferTo(res.raw().getOutputStream());
					res.raw().flushBuffer();
				}
				return null;
			} else {
				return servePage(res, childFolder);
			}
		});
	}

	private String servePage(Response res, File childFolder) {
		File[] files = childFolder.listFiles();
		if (files == null) {
			return notFound(res, "Could not list files under " + childFolder);
		}

		return page.render(
				controller.directoryState(childFolder),
				childFolder,
				Stream.of(files)
						.sorted(Comparator.comparing(file -> file.getName().toLowerCase()))
						.map(Item::new)
						.toList());
	}

	private String notFound(Response res, String error) {
		log.error(error);
		res.status(404);
		return "not found";
	}
}
