/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.values;

import org.junit.Test;

import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;

import akka.http.javadsl.model.*;
import akka.http.javadsl.model.headers.Age;
import akka.http.javadsl.model.headers.RawHeader;

public class HeadersTest extends JUnitRouteTest {
    RawHeader testHeaderInstance = RawHeader.create("X-Test-Header", "abcdef-test");
    Age ageHeaderInstance = Age.create(1000);

    @Test
    public void testValueByName() {
        TestRoute route = testRoute(headerValueByName("X-Test-Header", value -> complete(value)));

        route
            .run(HttpRequest.create())
            .assertStatusCode(400)
            .assertEntity("Request is missing required HTTP header 'X-Test-Header'");

        route
            .run(HttpRequest.create().addHeader(testHeaderInstance))
            .assertStatusCode(200)
            .assertEntity("abcdef-test");
    }
    @Test
    public void testOptionalValueByName() {
        TestRoute route = testRoute(optionalHeaderValueByName("X-Test-Header", value -> complete(value.toString())));

        route
            .run(HttpRequest.create())
            .assertStatusCode(200)
            .assertEntity("Optional.empty");

        route
            .run(HttpRequest.create().addHeader(testHeaderInstance))
            .assertStatusCode(200)
            .assertEntity("Optional[abcdef-test]");
    }
    
    @Test
    public void testValueByClass() {
        TestRoute route = testRoute(headerValueByType(Age.class, age -> complete(age.value())));

        route
            .run(HttpRequest.create())
            .assertStatusCode(400)
            .assertEntity("Request is missing required HTTP header 'Age'");

        route
            .run(HttpRequest.create().addHeader(ageHeaderInstance))
            .assertStatusCode(200)
            .assertEntity("1000");
    }
    @Test
    public void testOptionalValueByClass() {
        TestRoute route = testRoute(optionalHeaderValueByType(Age.class, age -> complete(age.toString())));

        route
            .run(HttpRequest.create())
            .assertStatusCode(200)
            .assertEntity("Optional.empty");

        route
            .run(HttpRequest.create().addHeader(ageHeaderInstance))
            .assertStatusCode(200)
            .assertEntity("Optional[Age=1000]");
    }
}
