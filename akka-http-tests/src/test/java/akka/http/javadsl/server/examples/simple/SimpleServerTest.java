/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.examples.simple;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.testkit.*;

import org.junit.Test;

public class SimpleServerTest extends JUnitRouteTest {
    TestRoute route = testRoute(new SimpleServerApp().createRoute());

    @Test
    public void testAdd() {
        TestRouteResult response = route.run(HttpRequest.GET("/add?x=42&y=23"));

        response
            .assertStatusCode(200)
            .assertEntity("42 + 23 = 65");
    }

    @Test
    public void testMultiplyAsync() {
        TestRouteResult response = route.run(HttpRequest.GET("/multiplyAsync/42/23"));

        response
            .assertStatusCode(200)
            .assertEntity("42 * 23 = 966");
    }

    @Test
    public void testPostWithBody() {
        TestRouteResult response = route.run(HttpRequest.POST("/hello").withEntity("John"));

        response
            .assertStatusCode(200)
            .assertEntity("Hello John!");
    }
}
