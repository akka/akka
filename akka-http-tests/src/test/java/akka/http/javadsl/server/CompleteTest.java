/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server;

import org.junit.Test;

import java.util.concurrent.CompletableFuture;

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
        Parameter<Integer> x = Parameters.intValue("x");
        Parameter<Integer> y = Parameters.intValue("y");

        Handler2<Integer, Integer> slowCalc = new Handler2<Integer, Integer>() {
            @Override
            public RouteResult apply(final RequestContext ctx, final Integer x, final Integer y) {
                return ctx.completeWith(CompletableFuture.supplyAsync(() -> {
                  int result = x + y;
                  return ctx.complete(String.format("%d + %d = %d",x, y, result));
                }, ctx.executionContext()));
            }
        };

        Route route = handleWith2(x, y, slowCalc);
        runRoute(route, HttpRequest.GET("add?x=42&y=23"))
            .assertStatusCode(200)
            .assertEntity("42 + 23 = 65");
    }
}
