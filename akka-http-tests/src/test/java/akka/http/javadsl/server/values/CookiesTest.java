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
    @Test
    public void testCookieValue() {
        TestRoute route =
            testRoute(
                cookie("userId", userId -> complete(userId.value()))
            );

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
            testRoute(
                optionalCookie("userId", opt -> complete(opt.toString()))
            );

        route.run(HttpRequest.create())
            .assertStatusCode(200)
            .assertEntity("Optional.empty");

        route.run(HttpRequest.create().addHeader(akka.http.javadsl.model.headers.Cookie.create("userId", "12345")))
            .assertStatusCode(200)
            .assertEntity("Optional[12345]");
    }
    @Test
    public void testCookieSet() {
        TestRoute route =
            testRoute(
                setCookie(HttpCookie.create("userId", "12"), () -> complete("OK!"))
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
                deleteCookie("userId", () -> complete("OK!"))
            );

        route.run(HttpRequest.create())
                .assertStatusCode(200)
                .assertHeaderExists("Set-Cookie", "userId=deleted; Expires=Wed, 01 Jan 1800 00:00:00 GMT; Domain=example.com; Path=/admin")
                .assertEntity("OK!");
    }
}
