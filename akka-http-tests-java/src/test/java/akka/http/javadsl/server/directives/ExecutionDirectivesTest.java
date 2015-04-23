/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server.directives;

import org.junit.Test;

import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.server.*;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.testkit.*;

import static akka.http.javadsl.server.Directives.*;

public class ExecutionDirectivesTest extends JUnitRouteTest {
    @Test
    public void testCatchExceptionThrownFromHandler() {
        Parameter<Integer> a = Parameters.integer("a");
        Parameter<Integer> b = Parameters.integer("b");
        Handler2<Integer, Integer> divide =
            new Handler2<Integer, Integer>() {
                @Override
                public RouteResult handle(RequestContext ctx, Integer a, Integer b) {
                    int result = a / b;
                    return ctx.complete("The result is: " + result);
                }
            };

        ExceptionHandler handleDivByZero =
            new ExceptionHandler() {
                @Override
                public Route handle(RuntimeException exception) {
                    try {
                        throw exception;
                    } catch(ArithmeticException t) {
                        return complete(
                                HttpResponse.create()
                                    .withStatus(400)
                                    .withEntity("Congratulations you provoked a division by zero!"));
                    }
                }
            };

        TestRoute route =
            testRoute(
                handleExceptions(handleDivByZero,
                    path("divide").route(
                        handleWith(a, b, divide)
                    )
                )
            );

        route.run(HttpRequest.GET("/divide?a=10&b=5"))
            .assertEntity("The result is: 2");

        route.run(HttpRequest.GET("/divide?a=10&b=0"))
            .assertStatusCode(400)
            .assertEntity("Congratulations you provoked a division by zero!");
    }
}
