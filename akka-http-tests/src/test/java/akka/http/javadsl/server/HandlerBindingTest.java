/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server;

import org.junit.Test;

import akka.http.scaladsl.model.HttpRequest;
import akka.http.javadsl.testkit.*;
import akka.http.javadsl.server.values.*;

public class HandlerBindingTest extends JUnitRouteTest {
    @Test
    public void testHandlerWithoutExtractions() {
        Route route = handleWith(
                new Handler() {
                    @Override
                    public RouteResult apply(RequestContext ctx) {
                        return ctx.complete("Ok");
                    }
                }
        );
        runRoute(route, HttpRequest.GET("/"))
            .assertEntity("Ok");
    }
    @Test
    public void testHandlerWithSomeExtractions() {
        final Parameter<Integer> a = Parameters.intValue("a");
        final Parameter<Integer> b = Parameters.intValue("b");

        Route route = handleWith(
                new Handler() {
                    @Override
                    public RouteResult apply(RequestContext ctx) {
                        return ctx.complete("Ok a:" + a.get(ctx) + " b:" + b.get(ctx));
                    }
                }, a, b
        );
        runRoute(route, HttpRequest.GET("?a=23&b=42"))
            .assertEntity("Ok a:23 b:42");
    }
    @Test
    public void testHandlerIfExtractionFails() {
        final Parameter<Integer> a = Parameters.intValue("a");

        Route route = handleWith(
                new Handler() {
                    @Override
                    public RouteResult apply(RequestContext ctx) {
                        return ctx.complete("Ok " + a.get(ctx));
                    }
                }, a
        );
        runRoute(route, HttpRequest.GET("/"))
            .assertStatusCode(404)
            .assertEntity("Request is missing required query parameter 'a'");
    }
    @Test
    public void testHandler1() {
        final Parameter<Integer> a = Parameters.intValue("a");

        Route route = handleWith1(a,
                new Handler1<Integer>() {
                    @Override
                    public RouteResult apply(RequestContext ctx, Integer a) {
                        return ctx.complete("Ok " + a);
                    }
                }
        );
        runRoute(route, HttpRequest.GET("?a=23"))
            .assertStatusCode(200)
            .assertEntity("Ok 23");
    }
    @Test
    public void testHandler2() {
        Route route = handleWith2(
                Parameters.intValue("a"),
                Parameters.intValue("b"),
                new Handler2<Integer, Integer>() {
                    @Override
                    public RouteResult apply(RequestContext ctx, Integer a, Integer b) {
                        return ctx.complete("Sum: " + (a + b));
                    }
                }
        );
        runRoute(route, HttpRequest.GET("?a=23&b=42"))
            .assertStatusCode(200)
            .assertEntity("Sum: 65");
    }
    @Test
    public void testHandler3() {
        Route route = handleWith3(
                Parameters.intValue("a"),
                Parameters.intValue("b"),
                Parameters.intValue("c"),
                new Handler3<Integer, Integer, Integer>() {
                    @Override
                    public RouteResult apply(RequestContext ctx, Integer a, Integer b, Integer c) {
                        return ctx.complete("Sum: " + (a + b + c));
                    }
                }
        );
        TestResponse response = runRoute(route, HttpRequest.GET("?a=23&b=42&c=30"));
        response.assertStatusCode(200);
        response.assertEntity("Sum: 95");
    }
    @Test
    public void testHandler4() {
        Route route = handleWith4(
                Parameters.intValue("a"),
                Parameters.intValue("b"),
                Parameters.intValue("c"),
                Parameters.intValue("d"),
                new Handler4<Integer, Integer, Integer, Integer>() {
                    @Override
                    public RouteResult apply(RequestContext ctx, Integer a, Integer b, Integer c, Integer d) {
                        return ctx.complete("Sum: " + (a + b + c + d));
                    }
                }
        );
        runRoute(route, HttpRequest.GET("?a=23&b=42&c=30&d=45"))
            .assertStatusCode(200)
            .assertEntity("Sum: 140");
    }
    @Test
    public void testReflectiveInstanceHandler() {
        class Test {
            public RouteResult negate(RequestContext ctx, int a) {
                return ctx.complete("Negated: " + (- a));
            }
        }
        Route route = handleReflectively(new Test(), "negate", Parameters.intValue("a"));
        runRoute(route, HttpRequest.GET("?a=23"))
            .assertStatusCode(200)
            .assertEntity("Negated: -23");
    }

    public static RouteResult squared(RequestContext ctx, int a) {
        return ctx.complete("Squared: " + (a * a));
    }
    @Test
    public void testStaticReflectiveHandler() {
        Route route = handleReflectively(HandlerBindingTest.class, "squared", Parameters.intValue("a"));
        runRoute(route, HttpRequest.GET("?a=23"))
            .assertStatusCode(200)
            .assertEntity("Squared: 529");
    }
}
