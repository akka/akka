/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server;

import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.PathMatchers;
import akka.http.javadsl.testkit.JUnitRouteTest;

import org.junit.Test;

public class PathDirectiveExampleTest extends JUnitRouteTest {
  @Test
  public void testPathPrefix() {
    //#path-examples
    // matches "/test"
    path("test", () ->
      complete(StatusCodes.OK)
    );

    // matches "/test", as well
    path(PathMatchers.segment("test"), () ->
      complete(StatusCodes.OK)
    );

    // matches "/admin/user"
    path(PathMatchers.segment("admin")
      .slash("user"), () ->
      complete(StatusCodes.OK)
    );

    // matches "/admin/user", as well
    pathPrefix("admin", () ->
      path("user", () ->
        complete(StatusCodes.OK)
      )
    );

    // matches "/admin/user/<user-id>"
    path(PathMatchers.segment("admin")
      .slash("user")
      .slash(PathMatchers.integerSegment()), userId -> {
        return complete("Hello user " + userId);
      }
    );

    // matches "/admin/user/<user-id>", as well
    pathPrefix("admin", () ->
      path("user", () ->
        path(PathMatchers.integerSegment(), userId ->
          complete("Hello user " + userId)
        )
      )
    );

    // never matches
    path("admin", () -> // oops this only matches "/admin", and no sub-paths
      path("user", () ->
        complete(StatusCodes.OK)
      )
    );

    // matches "/user/" with the first subroute, "/user" (without a trailing slash)
    // with the second subroute, and "/user/<user-id>" with the last one.
    pathPrefix("user", () -> route(
      pathSingleSlash(() ->
        complete(StatusCodes.OK)
      ),
      pathEnd(() ->
        complete(StatusCodes.OK)
      ),
      path(PathMatchers.integerSegment(), userId ->
        complete("Hello user " + userId)
      )
    ));
    //#path-examples
  }
}
