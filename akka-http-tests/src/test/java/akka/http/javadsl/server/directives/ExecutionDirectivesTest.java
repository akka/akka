/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives;

import org.junit.Test;

import akka.http.javadsl.model.*;
import akka.http.javadsl.server.*;
import akka.http.javadsl.server.values.*;
import akka.http.javadsl.testkit.*;

public class ExecutionDirectivesTest extends JUnitRouteTest {
    @Test
    public void testCatchExceptionThrownFromHandler() {
        Parameter<Integer> a = Parameters.intValue("a");
        Parameter<Integer> b = Parameters.intValue("b");
        Handler2<Integer, Integer> divide =
            new Handler2<Integer, Integer>() {
                @Override
                public RouteResult apply(RequestContext ctx, Integer a, Integer b) {
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
                        handleWith2(a, b, divide)
                    )
                )
            );

        route.run(HttpRequest.GET("/divide?a=10&b=5"))
            .assertEntity("The result is: 2");

        route.run(HttpRequest.GET("/divide?a=10&b=0"))
            .assertStatusCode(400)
            .assertEntity("Congratulations you provoked a division by zero!");
    }

    @Test
    public void testHandleMethodRejection() {
        RejectionHandler rejectionHandler =
            new RejectionHandler() {
                @Override
                public RouteResult handleMethodRejection(RequestContext ctx, HttpMethod supported) {
                    return ctx.complete(
                        HttpResponse.create()
                            .withStatus(400)
                            .withEntity("Whoopsie! Unsupported method. Supported would have been " + supported.value()));
                }
            };

        TestRoute route =
            testRoute(
               handleRejections(rejectionHandler,
                   get(complete("Successful!"))
               )
            );

        route.run(HttpRequest.GET("/"))
            .assertStatusCode(200)
            .assertEntity("Successful!");

        route.run(HttpRequest.POST("/"))
            .assertStatusCode(400)
            .assertEntity("Whoopsie! Unsupported method. Supported would have been GET");
    }

    public static final class TooManyRequestsRejection extends CustomRejection {
        final public String message;
        TooManyRequestsRejection(String message) {
            this.message = message;
        }
    }

    private static Handler testHandler =
        new Handler() {
            @Override
            public RouteResult apply(RequestContext ctx) {
                if (ctx.request().getUri().path().startsWith("/test"))
                    return ctx.complete("Successful!");
                else
                    return ctx.reject(new TooManyRequestsRejection("Too many requests for busy path!"));
            }
        };

    @Test
    public void testHandleCustomRejection() {
        RejectionHandler rejectionHandler =
            new RejectionHandler() {
                @Override
                public RouteResult handleCustomRejection(RequestContext ctx, CustomRejection rejection) {
                    if (rejection instanceof TooManyRequestsRejection) {
                        TooManyRequestsRejection rej = (TooManyRequestsRejection) rejection;
                        HttpResponse response =
                            HttpResponse.create()
                                .withStatus(StatusCodes.TOO_MANY_REQUESTS)
                                .withEntity(rej.message);
                        return ctx.complete(response);
                    } else
                        return passRejection();
                }
            };

        testRouteWithHandler(handleRejections(rejectionHandler, handleWith(testHandler)));
    }
    @Test
    public void testHandleCustomRejectionByClass() {
        Handler1<TooManyRequestsRejection> rejectionHandler =
            new Handler1<TooManyRequestsRejection>() {
                public RouteResult apply(RequestContext ctx, TooManyRequestsRejection rej) {
                    HttpResponse response =
                            HttpResponse.create()
                                    .withStatus(StatusCodes.TOO_MANY_REQUESTS)
                                    .withEntity(rej.message);
                    return ctx.complete(response);
                }
            };
        testRouteWithHandler(handleRejections(TooManyRequestsRejection.class, rejectionHandler, handleWith(testHandler)));
    }

    private void testRouteWithHandler(Route innerRoute) {
        TestRoute route = testRoute(innerRoute);

        route.run(HttpRequest.GET("/test"))
                .assertStatusCode(200);

        route.run(HttpRequest.GET("/other"))
                .assertStatusCode(429)
                .assertEntity("Too many requests for busy path!");
    }
}
