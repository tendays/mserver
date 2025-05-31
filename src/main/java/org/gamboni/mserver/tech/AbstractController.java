/**
 * 
 */
package org.gamboni.mserver.tech;

import org.gamboni.tech.web.js.JavaScript;
import org.gamboni.tech.web.ui.AbstractPage;
import org.gamboni.tech.web.ui.ScriptMember;
import spark.Response;
import spark.Spark;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.gamboni.tech.web.js.JavaScript.Fun;
import static org.gamboni.tech.web.js.JavaScript.JsExpression;
import static org.gamboni.tech.web.js.JavaScript.JsStatement;
import static org.gamboni.tech.web.js.JavaScript._if;
import static org.gamboni.tech.web.js.JavaScript.lambda;
import static org.gamboni.tech.web.js.JavaScript.let;
import static org.gamboni.tech.web.js.JavaScript.literal;
import static org.gamboni.tech.web.js.JavaScript.newXMLHttpRequest;
import static org.gamboni.tech.web.js.JavaScript.seq;

/** A "controller" exposes back-end functionality to the front end.
 *
 * @author tendays
 */
public class AbstractController {
	private final List<ScriptMember> jsProxy = new ArrayList<>();
	public final Mapping mapping;

	protected AbstractController(Mapping mapping) {
		this.mapping = mapping;
	}

	protected interface ServiceBody {
		Object execute() throws IOException;
	}
	
	protected interface ParamServiceBody {
		/** Execute the service with the given front-end-provided param value. */
		Object execute(String param) throws IOException;
	}
	
	
	protected interface ServiceProxy {
		/** Generate the Javascript code calling this service with the given parameter. */
		JsExpression call(JsExpression param);
	}
	
	public interface CallbackServiceProxy {
		JsExpression call(Function<JsExpression, JsStatement> callbackBody);
	}
	
	protected JsExpression service(String name, ServiceBody serverBody) {
		var fun = new Fun(name);
		jsProxy.add(
				fun.declare(
						let(newXMLHttpRequest(),
								r -> seq(
										r.invoke("open",
												literal("POST"),
												literal("/"+ name),
												literal(true)),
				r.invoke("send", literal(""))))));

		Spark.post("/"+ name, (req, res) -> json(res, serverBody.execute()));
		return fun.invoke();
	}

	private String json(Response res, Object result) {
		res.type("application/json");
		return mapping.writeValueAsString(result);
	}
	
	protected ServiceProxy service(String name, ParamServiceBody serviceBody) {
		var fun = new JavaScript.Fun1(name);
		jsProxy.add(
				fun.declare(arg ->
						let(newXMLHttpRequest(),
								r -> seq(
										r.invoke("open",
												literal("POST"),
												literal("/"+ name),
												literal(true)),
				r.invoke("send", arg)))));
		
		Spark.post("/"+ name, (req, res) -> json(res, serviceBody.execute(req.body())));
		
		return fun::invoke;
	}
	
	protected CallbackServiceProxy getService(String name, ServiceBody serviceBody) {
		var fun = new JavaScript.Fun1(name);
		jsProxy.add(
				fun.declare(callback ->
						let(newXMLHttpRequest(),
								r -> seq(
										r.dot("onreadystatechange").set(
												lambda(
														_if(
																r.dot("readyState").eq(literal(4))
																		.and(r.dot("status").eq(literal(200))),
																JavaScript.invoke(callback,
																		r.dot("responseText"))
														)
												)
										),
										r.invoke("open",
												literal("GET"),
												literal("/" + name),
												literal(true)),
										r.invoke("send", literal(""))))));
		Spark.get("/" + name, (req, res) -> json(res, serviceBody.execute()));

		String resultVariable = "result";

		return callbackBody ->
				fun.invoke(lambda(resultVariable, callbackBody));
	}

	public void addTo(AbstractPage<?> page) {
		jsProxy.forEach(page::addToScript);
	}
}
