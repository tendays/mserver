/**
 * 
 */
package org.gamboni.mserver.tech;

import org.gamboni.tech.web.js.JavaScript;
import spark.Response;
import spark.Spark;

import java.io.IOException;
import java.util.function.Function;

import static org.gamboni.tech.web.js.JavaScript.*;

/**
 * @author tendays
 *
 */
public class AbstractController {
	private final StringBuilder jsProxy = new StringBuilder();
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
		jsProxy.append(
				fun.declare(() ->
						let(newXMLHttpRequest(),
								JsExpression::of,
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
		jsProxy.append(
				fun.declare(arg ->
						let(newXMLHttpRequest(),
								JavaScript.JsExpression::of,
								r -> seq(
										r.invoke("open",
												literal("POST"),
												literal("/"+ name),
												literal(true)),
				r.invoke("send", arg)))));
		
		Spark.post("/"+ name, (req, res) -> json(res, serviceBody.execute(req.body())));
		
		return arg -> s -> name +"("+ arg.format(s) +")";
	}
	
	protected CallbackServiceProxy getService(String name, ServiceBody serviceBody) {
		var fun = new JavaScript.Fun1(name);
		jsProxy.append(
				fun.declare(callback ->
						let(newXMLHttpRequest(),
								JavaScript.JsExpression::of,
								r -> seq(
										r.dot("onreadystatechange").set(
												lambda(
														_if(
																r.dot("readyState").eq(literal(4))
																		.and(r.dot("status").eq(literal(200))),
																(JsExpression) (s -> callback.format(s) + "(" +
																		r.dot("responseText").format(s) + ")")
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

		return callbackBody -> s -> name + "(" +
				lambda(resultVariable, callbackBody)
						.format(s) +")";
	}

	public String getJavascript() {
		return this.jsProxy.toString();
	}
}
