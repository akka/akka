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
import akka.http.javadsl.server.values.*;

public class HeadersTest extends JUnitRouteTest {
    Header<HttpHeader> XTestHeader = Headers.byName("X-Test-Header");
    Header<Age> AgeHeader = Headers.byClass(Age.class);

    RawHeader testHeaderInstance = RawHeader.create("X-Test-Header", "abcdef-test");
    Age ageHeaderInstance = Age.create(1000);

    @Test
    public void testValueByName() {
        TestRoute route = testRoute(completeWithValueToString(XTestHeader.value()));

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
        TestRoute route = testRoute(completeWithValueToString(XTestHeader.optionalValue()));

        route
            .run(HttpRequest.create())
            .assertStatusCode(200)
            .assertEntity("None");

        route
            .run(HttpRequest.create().addHeader(testHeaderInstance))
            .assertStatusCode(200)
            .assertEntity("Some(abcdef-test)");
    }
    @Test
    public void testInstanceByName() {
        TestRoute route = testRoute(completeWithValueToString(XTestHeader.instance()));

        route
            .run(HttpRequest.create())
            .assertStatusCode(400)
            .assertEntity("Request is missing required HTTP header 'X-Test-Header'");

        route
            .run(HttpRequest.create().addHeader(testHeaderInstance))
            .assertStatusCode(200)
            .assertEntity("X-Test-Header: abcdef-test");
    }
    @Test
    public void testOptionalInstanceByName() {
        TestRoute route = testRoute(completeWithValueToString(XTestHeader.optionalInstance()));

        route
            .run(HttpRequest.create())
            .assertStatusCode(200)
            .assertEntity("None");

        route
            .run(HttpRequest.create().addHeader(testHeaderInstance))
            .assertStatusCode(200)
            .assertEntity("Some(X-Test-Header: abcdef-test)");
    }
    @Test
    public void testValueByClass() {
        TestRoute route = testRoute(completeWithValueToString(AgeHeader.value()));

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
        TestRoute route = testRoute(completeWithValueToString(AgeHeader.optionalValue()));

        route
            .run(HttpRequest.create())
            .assertStatusCode(200)
            .assertEntity("None");

        route
            .run(HttpRequest.create().addHeader(ageHeaderInstance))
            .assertStatusCode(200)
            .assertEntity("Some(1000)");
    }
    @Test
    public void testInstanceByClass() {
        TestRoute route = testRoute(completeWithValueToString(AgeHeader.instance()));

        route
            .run(HttpRequest.create())
            .assertStatusCode(400)
            .assertEntity("Request is missing required HTTP header 'Age'");

        route
            .run(HttpRequest.create().addHeader(ageHeaderInstance))
            .assertStatusCode(200)
            .assertEntity("Age: 1000");
    }
    @Test
    public void testOptionalInstanceByClass() {
        TestRoute route = testRoute(completeWithValueToString(AgeHeader.optionalInstance()));

        route
            .run(HttpRequest.create())
            .assertStatusCode(200)
            .assertEntity("None");

        route
            .run(HttpRequest.create().addHeader(ageHeaderInstance))
            .assertStatusCode(200)
            .assertEntity("Some(Age: 1000)");
    }
}
