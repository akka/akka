/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server;

import akka.http.scaladsl.model.HttpRequest;
import org.junit.Test;
import akka.http.javadsl.testkit.*;
import akka.http.javadsl.server.values.*;

public class HandlerBindingTest extends JUnitRouteTest {
    Parameter<Integer> aParam = Parameters.intValue("a");
    Parameter<Integer> bParam = Parameters.intValue("b");
    Parameter<Integer> cParam = Parameters.intValue("c");
    Parameter<Integer> dParam = Parameters.intValue("d");
    
    @Test
    public void testHandlerWithoutExtractions() {
        Route route = handleWith(ctx -> ctx.complete("Ok"));
        TestResponse response = runRoute(route, HttpRequest.GET("/"));
        response.assertEntity("Ok");
    }
    @Test
    public void testHandler1() {
        Route route = handleWith1(aParam, (ctx, a) -> ctx.complete("Ok " + a));
        TestResponse response = runRoute(route, HttpRequest.GET("?a=23"));
        response.assertStatusCode(200);
        response.assertEntity("Ok 23");
    }
    @Test
    public void testHandler2() {
        Route route =
            handleWith2(
                aParam,
                bParam,
                (ctx, a, b) -> ctx.complete("Sum: " + (a + b)));
        TestResponse response = runRoute(route, HttpRequest.GET("?a=23&b=42"));
        response.assertStatusCode(200);
        response.assertEntity("Sum: 65");
    }
    @Test
    public void testHandler3() {
        Route route =
            handleWith3(
                    aParam,
                    bParam,
                    cParam,
                    (ctx, a, b, c) -> ctx.complete("Sum: " + (a + b + c)));
        TestResponse response = runRoute(route, HttpRequest.GET("?a=23&b=42&c=30"));
        response.assertStatusCode(200);
        response.assertEntity("Sum: 95");
    }
    @Test
    public void testHandler4() {
        Route route =
            handleWith4(
                    aParam,
                    bParam,
                    cParam,
                    dParam,
                    (ctx, a, b, c, d) -> ctx.complete("Sum: " + (a + b + c + d)));
        TestResponse response = runRoute(route, HttpRequest.GET("?a=23&b=42&c=30&d=45"));
        response.assertStatusCode(200);
        response.assertEntity("Sum: 140");
    }
    public RouteResult sum(RequestContext ctx, int a, int b, int c, int d) {
        return ctx.complete("Sum: "+(a + b + c + d));
    }
    @Test
    public void testHandler4MethodRef() {
        Route route =
                handleWith4(
                        aParam,
                        bParam,
                        cParam,
                        dParam,
                        this::sum);
        TestResponse response = runRoute(route, HttpRequest.GET("?a=23&b=42&c=30&d=45"));
        response.assertStatusCode(200);
        response.assertEntity("Sum: 140");
    }
}
