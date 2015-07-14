/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.javadsl.server;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.server.*;
import akka.http.javadsl.server.values.Parameters;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import org.junit.Test;

public class HandlerExampleSpec extends JUnitRouteTest {
    @Test
    public void testCalculator() {
        //#handler2-example-full
        class TestHandler extends akka.http.javadsl.server.AllDirectives {
            RequestVal<Integer> xParam = Parameters.intValue("x");
            RequestVal<Integer> yParam = Parameters.intValue("y");

            //#handler2
            Handler2<Integer, Integer> multiply =
                (ctx, x, y) -> ctx.complete("x * y = " + (x * y));

            Route multiplyXAndYParam = handleWith(xParam, yParam, multiply);
            //#handler2

            Route createRoute() {
                return route(
                    get(
                        pathPrefix("calculator").route(
                            path("multiply").route(
                                multiplyXAndYParam
                            ),
                            path("add").route(
                                handleWith(xParam, yParam,
                                    (ctx, x, y) -> ctx.complete("x + y = " + (x + y)))
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
        //#handler2-example-full
    }
}