/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.values;

import akka.http.javadsl.model.DateTime;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.headers.HttpCookie;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import org.junit.Test;

public class CookiesTest extends JUnitRouteTest {
    Cookie userIdCookie = Cookies.create("userId").withDomain("example.com").withPath("/admin");
    
    @Test
    public void testCookieValue() {
        TestRoute route =
            testRoute(completeWithValueToString(userIdCookie.value()));

        route.run(HttpRequest.create())
            .assertStatusCode(400)
            .assertEntity("Request is missing required cookie 'userId'");

        route.run(HttpRequest.create().addHeader(akka.http.javadsl.model.headers.Cookie.create("userId", "12345")))
                .assertStatusCode(200)
                .assertEntity("12345");
    }
    @Test
    public void testCookieOptionalValue() {
        TestRoute route =
            testRoute(completeWithValueToString(userIdCookie.optionalValue()));

        route.run(HttpRequest.create())
            .assertStatusCode(200)
            .assertEntity("None");

        route.run(HttpRequest.create().addHeader(akka.http.javadsl.model.headers.Cookie.create("userId", "12345")))
            .assertStatusCode(200)
            .assertEntity("Some(12345)");
    }
    @Test
    public void testCookieSet() {
        TestRoute route =
                testRoute(
                        userIdCookie.set("12").route(
                                complete("OK!")
                        )
                );

        route.run(HttpRequest.create())
                .assertStatusCode(200)
                .assertHeaderExists("Set-Cookie", "userId=12; Domain=example.com; Path=/admin")
                .assertEntity("OK!");
    }
    @Test
    public void testDeleteCookie() {
        TestRoute route =
            testRoute(
                userIdCookie.delete(
                    complete("OK!")
                )
            );

        route.run(HttpRequest.create())
                .assertStatusCode(200)
                .assertHeaderExists("Set-Cookie", "userId=deleted; Expires=Wed, 01 Jan 1800 00:00:00 GMT; Domain=example.com; Path=/admin")
                .assertEntity("OK!");
    }
}
