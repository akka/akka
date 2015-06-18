/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server.directives;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.Uri;
import akka.http.javadsl.model.headers.Location;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import org.junit.Test;

public class RouteDirectivesTest extends JUnitRouteTest {
    @Test
    public void testRedirection() {
        Uri targetUri = Uri.create("http://example.com");
        TestRoute route =
            testRoute(
                redirect(targetUri, StatusCodes.FOUND)
            );

        route
            .run(HttpRequest.create())
            .assertStatusCode(302)
            .assertHeaderExists(Location.create(targetUri));
    }
}
