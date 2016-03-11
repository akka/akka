/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.values;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.RemoteAddress;
import akka.http.javadsl.model.Uri;
import akka.http.javadsl.model.headers.RawHeader;
import akka.http.javadsl.model.headers.XForwardedFor;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;

import org.junit.Test;
import akka.http.javadsl.server.Unmarshaller;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

public class RequestValTest extends JUnitRouteTest {
    @Test
    public void testSchemeExtraction() {
        TestRoute route = testRoute(extractScheme(s -> complete(s)));

        route
            .run(HttpRequest.create().withUri(Uri.create("http://example.org")))
            .assertStatusCode(200)
            .assertEntity("http");

        route
            .run(HttpRequest.create().withUri(Uri.create("https://example.org")))
            .assertStatusCode(200)
            .assertEntity("https");
    }

    @Test
    public void testHostExtraction() {
        TestRoute route = testRoute(extractHost(s -> complete(s)));

        route
            .run(HttpRequest.create().withUri(Uri.create("http://example.org")))
            .assertStatusCode(200)
            .assertEntity("example.org");
    }

    @Test
    public void testHostPatternExtraction() {
        TestRoute route =
            testRoute(host(Pattern.compile(".*\\.([^.]*)"), s -> complete(s)));

        route
            .run(HttpRequest.create().withUri(Uri.create("http://example.org")))
            .assertStatusCode(200)
            .assertEntity("org");

        route
            .run(HttpRequest.create().withUri(Uri.create("http://example.de")))
            .assertStatusCode(200)
            .assertEntity("de");
    }

    @Test
    public void testClientIpExtraction() throws UnknownHostException{
        TestRoute route = testRoute(extractClientIP(ip -> complete(ip.toString())));

        route
            .run(HttpRequest.create().addHeader(XForwardedFor.create(RemoteAddress.create(InetAddress.getByName("127.0.0.2")))))
            .assertStatusCode(200)
            .assertEntity("127.0.0.2");

        route
            .run(HttpRequest.create().addHeader(akka.http.javadsl.model.headers.RemoteAddress.create(RemoteAddress.create(InetAddress.getByName("127.0.0.3")))))
            .assertStatusCode(200)
            .assertEntity("127.0.0.3");

        route
            .run(HttpRequest.create().addHeader(RawHeader.create("X-Real-IP", "127.0.0.4")))
            .assertStatusCode(200)
            .assertEntity("127.0.0.4");

        route
            .run(HttpRequest.create())
            .assertStatusCode(404);
    }

    @Test
    public void testEntityAsString() {
        TestRoute route =
            testRoute(
                entity(Unmarshaller.entityToString(), s -> complete(s))
            );

        HttpRequest request =
            HttpRequest.POST("/")
                .withEntity("abcdef");
        route.run(request)
                .assertStatusCode(200)
                .assertEntity("abcdef");
    }
}
