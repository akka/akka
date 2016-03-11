/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives;

import org.junit.Test;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.Uri;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;

public class MiscDirectivesTest extends JUnitRouteTest {

    static boolean isShort(String str) {
        return str.length() < 5;
    }

    static boolean hasShortPath(Uri uri) {
        return uri.path().toString().length() < 5;
    }
        
    @Test
    public void testValidateUri() {
        TestRoute route = testRoute(
            extractUri(uri -> 
                validate(() -> hasShortPath(uri), "Path too long!", 
                    () -> complete("OK!")
                )
            )
        );

        route
            .run(HttpRequest.create().withUri(Uri.create("/abc")))
            .assertStatusCode(200)
            .assertEntity("OK!");

        route
            .run(HttpRequest.create().withUri(Uri.create("/abcdefghijkl")))
            .assertStatusCode(400)
            .assertEntity("Path too long!");
    }
}
