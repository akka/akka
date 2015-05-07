/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server;

import org.junit.Test;

import java.util.concurrent.Callable;
import akka.dispatch.Futures;
import akka.http.javadsl.testkit.*;

import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.MediaTypes;
import akka.http.javadsl.server.values.*;

public class CompleteTest extends JUnitRouteTest {
    @Test
    public void completeWithString() {
        Route route = complete("Everything OK!");

        HttpRequest request = HttpRequest.create();

        runRoute(route, request)
            .assertStatusCode(200)
            .assertMediaType(MediaTypes.TEXT_PLAIN)
            .assertEntity("Everything OK!");
    }

    @Test
    public void completeAsJacksonJson() {
        class Person {
            public String getFirstName() { return "Peter"; }
            public String getLastName() { return "Parker"; }
            public int getAge() { return 138; }
        }
        Route route = completeAs(Jackson.json(), new Person());

        HttpRequest request = HttpRequest.create();

        runRoute(route, request)
            .assertStatusCode(200)
            .assertMediaType("application/json")
            .assertEntity("{\"age\":138,\"firstName\":\"Peter\",\"lastName\":\"Parker\"}");
    }
    @Test
    public void completeWithFuture() {
        Parameter<Integer> x = Parameters.integer("x");
        Parameter<Integer> y = Parameters.integer("y");

        Handler2<Integer, Integer> slowCalc = new Handler2<Integer, Integer>() {
            @Override
            public RouteResult handle(final RequestContext ctx, final Integer x, final Integer y) {
                return ctx.completeWith(Futures.future(new Callable<RouteResult>() {
                    @Override
                    public RouteResult call() throws Exception {
                        int result = x + y;
                        return ctx.complete(String.format("%d + %d = %d",x, y, result));
                    }
                }, executionContext()));
            }
        };

        Route route = handleWith(x, y, slowCalc);
        runRoute(route, HttpRequest.GET("add?x=42&y=23"))
            .assertStatusCode(200)
            .assertEntity("42 + 23 = 65");
    }
}
