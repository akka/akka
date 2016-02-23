/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server;

import akka.dispatch.Mapper;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.*;
import akka.http.javadsl.server.values.Parameters;
import akka.http.javadsl.server.values.PathMatchers;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.junit.Test;

public class HandlerExampleDocTest extends JUnitRouteTest {
    @Test
    public void testSimpleHandler() {
        //#simple-handler-example-full
        class TestHandler extends akka.http.javadsl.server.AllDirectives {
            //#simple-handler
            Handler handlerString = new Handler() {
                static final long serialVersionUID = 1L;
                @Override
                public RouteResult apply(RequestContext ctx) {
                    return ctx.complete(String.format("This was a %s request to %s",
                      ctx.request().method().value(), ctx.request().getUri()));
                }
            };
            Handler handlerResponse = new Handler() {
                static final long serialVersionUID = 1L;
                @Override
                public RouteResult apply(RequestContext ctx) {
                    // with full control over the returned HttpResponse:
                    final HttpResponse response = HttpResponse.create()
                        .withEntity(String.format("Accepted %s request to %s",
                          ctx.request().method().value(), ctx.request().getUri()))
                        .withStatus(StatusCodes.ACCEPTED);
                    return ctx.complete(response);
                }
            };
            //#simple-handler

            Route createRoute() {
                return route(
                    get(
                        handleWith(handlerString)
                    ),
                    post(
                        path("abc").route(
                            handleWith(handlerResponse)
                        )
                    )
                );
            }
        }

        // actual testing code
        TestRoute r = testRoute(new TestHandler().createRoute());
        r.run(HttpRequest.GET("/test"))
            .assertStatusCode(200)
            .assertEntity("This was a GET request to http://example.com/test");

        r.run(HttpRequest.POST("/test"))
            .assertStatusCode(404);

        r.run(HttpRequest.POST("/abc"))
            .assertStatusCode(202)
            .assertEntity("Accepted POST request to http://example.com/abc");
        //#simple-handler-example-full
    }

    @Test
    public void testCalculator() {
        //#handler2-example-full
        class TestHandler extends akka.http.javadsl.server.AllDirectives {
            final RequestVal<Integer> xParam = Parameters.intValue("x");
            final RequestVal<Integer> yParam = Parameters.intValue("y");

            final RequestVal<Integer> xSegment = PathMatchers.intValue();
            final RequestVal<Integer> ySegment = PathMatchers.intValue();

            //#handler2
            final Handler2<Integer, Integer> multiply =
                new Handler2<Integer, Integer>() {
                    static final long serialVersionUID = 1L;
                    @Override
                    public RouteResult apply(RequestContext ctx, Integer x, Integer y) {
                        int result = x * y;
                        return ctx.complete("x * y = " + result);
                    }
                };

            final Route multiplyXAndYParam = handleWith2(xParam, yParam, multiply);
            //#handler2

            Route createRoute() {
                return route(
                    get(
                        pathPrefix("calculator").route(
                            path("multiply").route(
                                multiplyXAndYParam
                            ),
                            path("path-multiply", xSegment, ySegment).route(
                                handleWith2(xSegment, ySegment, multiply)
                            )
                        )
                    )
                );
            }
        }

        // actual testing code
        TestRoute r = testRoute(new TestHandler().createRoute());
        r.run(HttpRequest.GET("/calculator/multiply?x=12&y=42"))
            .assertStatusCode(200)
            .assertEntity("x * y = 504");

        r.run(HttpRequest.GET("/calculator/path-multiply/23/5"))
            .assertStatusCode(200)
            .assertEntity("x * y = 115");
        //#handler2-example-full
    }

    @Test
    public void testCalculatorJava8() {
        //#handler2-java8-example-full
        class TestHandler extends akka.http.javadsl.server.AllDirectives {
            final RequestVal<Integer> xParam = Parameters.intValue("x");
            final RequestVal<Integer> yParam = Parameters.intValue("y");

            //#handler2-java8
            final Handler2<Integer, Integer> multiply =
                    (ctx, x, y) -> ctx.complete("x * y = " + (x * y));

            final Route multiplyXAndYParam = handleWith2(xParam, yParam, multiply);
            //#handler2-java8

            RouteResult subtract(RequestContext ctx, int x, int y) {
                return ctx.complete("x - y = " + (x - y));
            }

            Route createRoute() {
                return route(
                    get(
                        pathPrefix("calculator").route(
                            path("multiply").route(
                                // use Handler explicitly
                                multiplyXAndYParam
                            ),
                            path("add").route(
                                // create Handler as lambda expression
                                handleWith2(xParam, yParam,
                                        (ctx, x, y) -> ctx.complete("x + y = " + (x + y)))
                            ),
                            path("subtract").route(
                                // create handler by lifting method
                                handleWith2(xParam, yParam, this::subtract)
                            )
                        )
                    )
                );
            }
        }

        // actual testing code
        TestRoute r = testRoute(new TestHandler().createRoute());
        r.run(HttpRequest.GET("/calculator/multiply?x=12&y=42"))
                .assertStatusCode(200)
                .assertEntity("x * y = 504");

        r.run(HttpRequest.GET("/calculator/add?x=12&y=42"))
                .assertStatusCode(200)
                .assertEntity("x + y = 54");

        r.run(HttpRequest.GET("/calculator/subtract?x=42&y=12"))
                .assertStatusCode(200)
                .assertEntity("x - y = 30");
        //#handler2-java8-example-full
    }

    @Test
    public void testCalculatorReflective() {
        //#reflective-example-full
        class TestHandler extends akka.http.javadsl.server.AllDirectives {
            RequestVal<Integer> xParam = Parameters.intValue("x");
            RequestVal<Integer> yParam = Parameters.intValue("y");

            RequestVal<Integer> xSegment = PathMatchers.intValue();
            RequestVal<Integer> ySegment = PathMatchers.intValue();


            //#reflective
            public RouteResult multiply(RequestContext ctx, Integer x, Integer y) {
                int result = x * y;
                return ctx.complete("x * y = " + result);
            }

            Route multiplyXAndYParam = handleReflectively(this, "multiply", xParam, yParam);
            //#reflective

            Route createRoute() {
                return route(
                    get(
                        pathPrefix("calculator").route(
                            path("multiply").route(
                                multiplyXAndYParam
                            ),
                            path("path-multiply", xSegment, ySegment).route(
                                handleWith2(xSegment, ySegment, this::multiply)
                            )
                        )
                    )
                );
            }
        }

        // actual testing code
        TestRoute r = testRoute(new TestHandler().createRoute());
        r.run(HttpRequest.GET("/calculator/multiply?x=12&y=42"))
            .assertStatusCode(200)
            .assertEntity("x * y = 504");

        r.run(HttpRequest.GET("/calculator/path-multiply/23/5"))
            .assertStatusCode(200)
            .assertEntity("x * y = 115");
        //#reflective-example-full
    }

    @Test
    public void testDeferredResultAsyncHandler() {
        //#async-example-full
        //#async-service-definition
        class CalculatorService {
            public CompletionStage<Integer> multiply(final int x, final int y) {
                return CompletableFuture.supplyAsync(() -> x * y);
            }

            public CompletionStage<Integer> add(final int x, final int y) {
                return CompletableFuture.supplyAsync(() -> x + y);
            }
        }
        //#async-service-definition

        class TestHandler extends akka.http.javadsl.server.AllDirectives {
            RequestVal<Integer> xParam = Parameters.intValue("x");
            RequestVal<Integer> yParam = Parameters.intValue("y");

            //#async-handler-1
            // would probably be injected or passed at construction time in real code
            CalculatorService calculatorService = new CalculatorService();
            public CompletionStage<RouteResult> multiplyAsync(final RequestContext ctx, int x, int y) {
                CompletionStage<Integer> result = calculatorService.multiply(x, y);
                return result.thenApplyAsync(product -> ctx.complete("x * y = " + product),
                    ctx.executionContext());
            }
            Route multiplyAsyncRoute =
                path("multiply").route(
                    handleWithAsync2(xParam, yParam, this::multiplyAsync)
                );
            //#async-handler-1

            //#async-handler-2
            public RouteResult addAsync(final RequestContext ctx, int x, int y) {
                CompletionStage<Integer> result = calculatorService.add(x, y);
                return ctx.completeWith(result.thenApplyAsync(sum -> ctx.complete("x + y = " + sum),
                    ctx.executionContext()));
            }
            Route addAsyncRoute =
                path("add").route(
                    handleWith2(xParam, yParam, this::addAsync)
                );
            //#async-handler-2

            Route createRoute() {
                return route(
                    get(
                        pathPrefix("calculator").route(
                            multiplyAsyncRoute,
                            addAsyncRoute
                        )
                    )
                );
            }
        }

        // testing code
        TestRoute r = testRoute(new TestHandler().createRoute());
        r.run(HttpRequest.GET("/calculator/multiply?x=12&y=42"))
            .assertStatusCode(200)
            .assertEntity("x * y = 504");

        r.run(HttpRequest.GET("/calculator/add?x=23&y=5"))
            .assertStatusCode(200)
            .assertEntity("x + y = 28");
        //#async-example-full
    }
}
