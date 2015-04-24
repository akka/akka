/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.japi.examples.simple;

import akka.http.model.japi.HttpRequest;
import akka.http.server.japi.JUnitRouteTest;
import akka.http.server.japi.TestResponse;
import akka.http.server.japi.TestRoute;
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
}
