/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server;

import static akka.http.javadsl.server.StringUnmarshallers.INTEGER;

import org.junit.Test;

import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRouteResult;
import akka.http.scaladsl.model.HttpRequest;

public class HandlerBindingTest extends JUnitRouteTest {
    
    @Test
    public void testHandlerWithoutExtractions() {
        Route route = complete("Ok");
        TestRouteResult response = runRoute(route, HttpRequest.GET("/"));
        response.assertEntity("Ok");
    }
    @Test
    public void testHandler1() {
        Route route = param("a", a -> complete("Ok " + a));
        TestRouteResult response = runRoute(route, HttpRequest.GET("?a=23"));
        response.assertStatusCode(200);
        response.assertEntity("Ok 23");
    }
    @Test
    public void testHandler2() {
        Route route = param(INTEGER, "a", a -> param(INTEGER, "b", b -> complete("Sum: " + (a + b))));
        TestRouteResult response = runRoute(route, HttpRequest.GET("?a=23&b=42"));
        response.assertStatusCode(200);
        response.assertEntity("Sum: 65");
    }
    
    public Route sum(int a, int b, int c, int d) {
        return complete("Sum: " + (a + b + c + d));
    }
    @Test
    public void testHandlerMethod() {
        Route route = param(INTEGER, "a", a ->
                      param(INTEGER, "b", b ->
                      param(INTEGER, "c", c ->
                      param(INTEGER, "d", d -> sum(a,b,c,d)))));
        TestRouteResult response = runRoute(route, HttpRequest.GET("?a=23&b=42&c=30&d=45"));
        response.assertStatusCode(200);
        response.assertEntity("Sum: 140");
    }
}
