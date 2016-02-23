/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server;

import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.Handler1;
import akka.http.javadsl.server.values.PathMatcher;
import akka.http.javadsl.server.values.PathMatchers;
import akka.http.javadsl.testkit.JUnitRouteTest;
import org.junit.Test;

public class PathDirectiveExampleTest extends JUnitRouteTest {
    @Test
    public void testPathPrefix() {
        //#path-examples
        // matches "/test"
        path("test").route(
            completeWithStatus(StatusCodes.OK)
        );

        // matches "/test", as well
        path(PathMatchers.segment("test")).route(
            completeWithStatus(StatusCodes.OK)
        );

        // matches "/admin/user"
        path("admin", "user").route(
            completeWithStatus(StatusCodes.OK)
        );

        // matches "/admin/user", as well
        pathPrefix("admin").route(
            path("user").route(
                completeWithStatus(StatusCodes.OK)
            )
        );

        // matches "/admin/user/<user-id>"
        Handler1<Integer> completeWithUserId =
          (ctx, userId) -> ctx.complete("Hello user " + userId);
        PathMatcher<Integer> userId = PathMatchers.intValue();
        pathPrefix("admin", "user").route(
            path(userId).route(
                handleWith1(userId, completeWithUserId)
            )
        );

        // matches "/admin/user/<user-id>", as well
        path("admin", "user", userId).route(
            handleWith1(userId, completeWithUserId)
        );

        // never matches
        path("admin").route( // oops this only matches "/admin"
            path("user").route(
                completeWithStatus(StatusCodes.OK)
            )
        );

        // matches "/user/" with the first subroute, "/user" (without a trailing slash)
        // with the second subroute, and "/user/<user-id>" with the last one.
        pathPrefix("user").route(
            pathSingleSlash().route(
                completeWithStatus(StatusCodes.OK)
            ),
            pathEnd().route(
                completeWithStatus(StatusCodes.OK)
            ),
            path(userId).route(
                handleWith1(userId, completeWithUserId)
            )
        );
        //#path-examples
    }
}