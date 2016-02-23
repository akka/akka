/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.examples.simple;

import akka.actor.ActorSystem;
import akka.http.javadsl.server.*;
import akka.http.javadsl.server.values.*;

import java.io.IOException;

public class SimpleServerApp8 extends HttpApp {
    static Parameter<Integer> x = Parameters.intValue("x");
    static Parameter<Integer> y = Parameters.intValue("y");

    static PathMatcher<Integer> xSegment = PathMatchers.intValue();
    static PathMatcher<Integer> ySegment = PathMatchers.intValue();

    public static RouteResult multiply(RequestContext ctx, int x, int y) {
        int result = x * y;
        return ctx.complete(String.format("%d * %d = %d", x, y, result));
    }

    static class Test {
        int constant;
        Test(int constant) {
            this.constant = constant;
        }
        RouteResult constantPlusMultiply(RequestContext ctx, int x, int y) {
            int result = x * y + constant;
            return ctx.complete(String.format("%d * %d + %d = %d", x, y, constant, result));
        }
    }

    public void test() {
        handleWith2(xSegment, ySegment, SimpleServerApp8::multiply);
    }

    @Override
    public Route createRoute() {
        Handler addHandler = new Handler() {
            static final long serialVersionUID = 1L;
            @Override
            public RouteResult apply(RequestContext ctx) {
                int xVal = x.get(ctx);
                int yVal = y.get(ctx);
                int result = xVal + yVal;
                return ctx.complete(String.format("%d + %d = %d", xVal, yVal, result));
            }
        };
        Handler2<Integer, Integer> subtractHandler = new Handler2<Integer, Integer>() {
            static final long serialVersionUID = 1L;
            public RouteResult apply(RequestContext ctx, Integer xVal, Integer yVal) {
                int result = xVal - yVal;
                return ctx.complete(String.format("%d - %d = %d", xVal, yVal, result));
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
                    handleWith(addHandler)
                ),
                path("subtract").route(
                    handleWith2(x, y, subtractHandler)
                ),
                path("divide").route(
                    handleWith2(x, y,
                            (ctx, x, y) ->
                                    ctx.complete(String.format("%d / %d = %d", x, y, x / y))
                    )
                ),
                // matches paths like this: /multiply/{x}/{y}
                path("multiply", xSegment, ySegment).route(
                    // bind handler by reflection
                    handleWith2(xSegment, ySegment, SimpleServerApp8::multiply)
                ),
                path("multiply-methodref", xSegment, ySegment).route(
                    // bind handler by reference to new instance of handler
                    handleWith2(xSegment, ySegment, new Test(123)::constantPlusMultiply)
                )
            );
    }

    public static void main(String[] args) throws IOException {
        ActorSystem system = ActorSystem.create();
        new SimpleServerApp8().bindRoute("localhost", 8080, system);
        System.out.println("Type RETURN to exit");
        System.in.read();
        system.terminate();
    }
}

