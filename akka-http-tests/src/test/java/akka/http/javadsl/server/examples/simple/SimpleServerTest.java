/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server.examples.simple;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.testkit.*;
import org.junit.Test;

public class SimpleServerTest extends JUnitRouteTest {
    TestRoute route = testRoute(new SimpleServerApp().createRoute());

    @Test
    public void testAdd() {
        TestResponse response = route.run(HttpRequest.GET("/add?x=42&y=23"));

        response
            .assertStatusCode(200)
            .assertEntity("42 + 23 = 65");
    }

    @Test
    public void testMultiplyAsync() {
        TestResponse response = route.run(HttpRequest.GET("/multiplyAsync/42/23"));

        response
            .assertStatusCode(200)
            .assertEntity("42 * 23 = 966");
    }
}
