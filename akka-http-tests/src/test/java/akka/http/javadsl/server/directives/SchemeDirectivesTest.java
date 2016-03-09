/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.Uri;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import org.junit.Test;

public class SchemeDirectivesTest extends JUnitRouteTest {
    @Test
    public void testSchemeFilter() {
        TestRoute route = testRoute(scheme("http", () -> complete("OK!")));

        route
            .run(HttpRequest.create().withUri(Uri.create("http://example.org")))
            .assertStatusCode(200)
            .assertEntity("OK!");

        route
            .run(HttpRequest.create().withUri(Uri.create("https://example.org")))
            .assertStatusCode(400)
            .assertEntity("Uri scheme not allowed, supported schemes: http");
    }
}
