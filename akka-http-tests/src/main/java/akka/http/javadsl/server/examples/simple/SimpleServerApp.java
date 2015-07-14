/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server.examples.simple;

import akka.actor.ActorSystem;
import akka.dispatch.Futures;
import akka.http.javadsl.server.*;
import akka.http.javadsl.server.values.Parameter;
import akka.http.javadsl.server.values.Parameters;
import akka.http.javadsl.server.values.PathMatcher;
import akka.http.javadsl.server.values.PathMatchers;
import scala.concurrent.Future;

import java.io.IOException;
import java.util.concurrent.Callable;

public class SimpleServerApp extends HttpApp {
    static Parameter<Integer> x = Parameters.intValue("x");
    static Parameter<Integer> y = Parameters.intValue("y");

    static PathMatcher<Integer> xSegment = PathMatchers.intValue();
    static PathMatcher<Integer> ySegment = PathMatchers.intValue();

    static RequestVal<String> bodyAsName = RequestVals.entityAs(Unmarshallers.String());

    public static RouteResult multiply(RequestContext ctx, int x, int y) {
        int result = x * y;
        return ctx.complete(String.format("%d * %d = %d", x, y, result));
    }
    public static Future<RouteResult> multiplyAsync(final RequestContext ctx, final int x, final int y) {
        return Futures.future(new Callable<RouteResult>() {
            public RouteResult call() throws Exception {
                return multiply(ctx, x, y);
            }
        }, ctx.executionContext());
    }

    @Override
    public Route createRoute() {
        Handler addHandler = new Handler() {
            @Override
            public RouteResult handle(RequestContext ctx) {
                int xVal = x.get(ctx);
                int yVal = y.get(ctx);
                int result = xVal + yVal;
                return ctx.complete(String.format("%d + %d = %d", xVal, yVal, result));
            }
        };
        Handler2<Integer, Integer> subtractHandler = new Handler2<Integer, Integer>() {
            public RouteResult handle(RequestContext ctx, Integer xVal, Integer yVal) {
                int result = xVal - yVal;
                return ctx.complete(String.format("%d - %d = %d", xVal, yVal, result));
            }
        };
        Handler1<String> helloPostHandler =
            new Handler1<String>() {
                @Override
                public RouteResult handle(RequestContext ctx, String s) {
                    return ctx.complete("Hello " + s + "!");
                }
            };
        return
            route(
                // matches the empty path
                pathSingleSlash().route(
                    getFromResource("web/calculator.html")
                ),
                // matches paths like this: /add?x=42&y=23
                path("add").route(
                    handleWith(addHandler, x, y)
                ),
                path("subtract").route(
                    handleWith(x, y, subtractHandler)
                ),
                // matches paths like this: /multiply/{x}/{y}
                path("multiply", xSegment, ySegment).route(
                    // bind handler by reflection
                    handleWith(SimpleServerApp.class, "multiply", xSegment, ySegment)
                ),
                path("multiplyAsync", xSegment, ySegment).route(
                    // bind async handler by reflection
                    handleWith(SimpleServerApp.class, "multiplyAsync", xSegment, ySegment)
                ),
                post(
                    path("hello").route(
                        handleWith(bodyAsName, helloPostHandler)
                    )
                )
            );
    }

    public static void main(String[] args) throws IOException {
        ActorSystem system = ActorSystem.create();
        new SimpleServerApp().bindRoute("localhost", 8080, system);
        System.out.println("Type RETURN to exit");
        System.in.read();
        system.shutdown();
    }
}