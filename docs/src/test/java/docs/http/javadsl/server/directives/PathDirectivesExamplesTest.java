/*
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server.directives;

import java.util.Arrays;
import java.util.regex.Pattern;

import akka.http.javadsl.server.PathMatchers;
import org.junit.Test;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import static akka.http.javadsl.server.PathMatchers.segment;
import static akka.http.javadsl.server.PathMatchers.segments;
import static akka.http.javadsl.server.PathMatchers.integerSegment;
import static akka.http.javadsl.server.PathMatchers.neutral;
import static akka.http.javadsl.server.PathMatchers.slash;
import java.util.function.Supplier;
import akka.http.javadsl.server.directives.RouteAdapter;
import static java.util.regex.Pattern.compile;

public class PathDirectivesExamplesTest extends JUnitRouteTest {

  //#path-prefix-test, path-suffix, raw-path-prefix, raw-path-prefix-test
  Supplier<RouteAdapter> completeWithUnmatchedPath = ()->
    extractUnmatchedPath((path) -> complete(path.toString()));
  //#path-prefix-test, path-suffix, raw-path-prefix, raw-path-prefix-test

  @Test
  public void testPathExamples() {
    //#path-dsl
    // matches /foo/
    path(segment("foo").slash(), () -> complete(StatusCodes.OK));

    // matches e.g. /foo/123 and extracts "123" as a String
    path(segment("foo").slash(segment(compile("\\d+"))), (value) -> 
        complete(StatusCodes.OK));

    // matches e.g. /foo/bar123 and extracts "123" as a String
    path(segment("foo").slash(segment(compile("bar(\\d+)"))), (value) -> 
        complete(StatusCodes.OK));
    
    // similar to `path(Segments)`
    path(neutral().repeat(0, 10), () -> complete(StatusCodes.OK));

    // identical to path("foo" ~ (PathEnd | Slash))
    path(segment("foo").orElse(slash()), () -> complete(StatusCodes.OK));
    //#path-dsl
  }

  @Test
  public void testBasicExamples() {
    path("test", () -> complete(StatusCodes.OK));
     
    // matches "/test", as well
    path(segment("test"), () -> complete(StatusCodes.OK));

  }

  @Test
  public void testPathExample() {
    //#pathPrefix
    final Route route = 
        route(
            path("foo", () -> complete("/foo")),
            path(segment("foo").slash("bar"), () -> complete("/foo/bar")),
            pathPrefix("ball", () -> 
                route(
                    pathEnd(() -> complete("/ball")),
                    path(integerSegment(), (i) -> 
                        complete((i % 2 == 0) ? "even ball" : "odd ball"))
                )
            )
        );

    // tests:
    testRoute(route).run(HttpRequest.GET("/")).assertStatusCode(StatusCodes.NOT_FOUND);
    testRoute(route).run(HttpRequest.GET("/foo")).assertEntity("/foo");
    testRoute(route).run(HttpRequest.GET("/foo/bar")).assertEntity("/foo/bar");
    testRoute(route).run(HttpRequest.GET("/ball/1337")).assertEntity("odd ball");
    //#pathPrefix
  }

  @Test
  public void testPathEnd() {
    //#path-end
    final Route route = 
        route(
            pathPrefix("foo", () -> 
                route(
                    pathEnd(() -> complete("/foo")),
                    path("bar", () -> complete("/foo/bar"))
                )
            )
        );

    // tests:
    testRoute(route).run(HttpRequest.GET("/foo")).assertEntity("/foo");
    testRoute(route).run(HttpRequest.GET("/foo/")).assertStatusCode(StatusCodes.NOT_FOUND);
    testRoute(route).run(HttpRequest.GET("/foo/bar")).assertEntity("/foo/bar");
    //#path-end
  }

  @Test
  public void testPathEndOrSingleSlash() {
    //#path-end-or-single-slash
    final Route route = 
        route(
            pathPrefix("foo", () -> 
                route(
                    pathEndOrSingleSlash(() -> complete("/foo")),
                    path("bar", () -> complete("/foo/bar"))
                )
            )
        );
    // tests:
    testRoute(route).run(HttpRequest.GET("/foo")).assertEntity("/foo");
    testRoute(route).run(HttpRequest.GET("/foo/")).assertEntity("/foo");
    testRoute(route).run(HttpRequest.GET("/foo/bar")).assertEntity("/foo/bar");
    //#path-end-or-single-slash
  }

  @Test
  public void testPathPrefix() {
    //#path-prefix
    final Route route = 
        route(
            pathPrefix("ball", () -> 
                route(
                    pathEnd(() -> complete("/ball")),
                    path(integerSegment(), (i) -> 
                        complete((i % 2 == 0) ? "even ball" : "odd ball"))
                )
            )
        );
    // tests:
    testRoute(route).run(HttpRequest.GET("/")).assertStatusCode(StatusCodes.NOT_FOUND);
    testRoute(route).run(HttpRequest.GET("/ball")).assertEntity("/ball");
    testRoute(route).run(HttpRequest.GET("/ball/1337")).assertEntity("odd ball");
    //#path-prefix
  }

  @Test
  public void testPathPrefixTest() {
    //#path-prefix-test
    final Route route = 
        route(
            pathPrefixTest(segment("foo").orElse("bar"), () ->
                route(
                      pathPrefix("foo", () -> completeWithUnmatchedPath.get()),
                      pathPrefix("bar", () -> completeWithUnmatchedPath.get())
                )
            )
        );
    // tests:
    testRoute(route).run(HttpRequest.GET("/foo/doo")).assertEntity("/doo");
    testRoute(route).run(HttpRequest.GET("/bar/yes")).assertEntity("/yes");
    //#path-prefix-test
  }

  @Test
  public void testPathSingleSlash() {
    //#path-single-slash
    final Route route = 
        route(
            pathSingleSlash(() -> complete("root")),
            pathPrefix("ball", () -> 
                route(
                    pathSingleSlash(() -> complete("/ball/")),
                    path(integerSegment(), (i) -> complete((i % 2 == 0) ? "even ball" : "odd ball"))
                )
            )
        );
    // tests:
    testRoute(route).run(HttpRequest.GET("/")).assertEntity("root");
    testRoute(route).run(HttpRequest.GET("/ball")).assertStatusCode(StatusCodes.NOT_FOUND);
    testRoute(route).run(HttpRequest.GET("/ball/")).assertEntity("/ball/");
    testRoute(route).run(HttpRequest.GET("/ball/1337")).assertEntity("odd ball");
    //#path-single-slash
  }

  @Test
  public void testPathSuffix() {
    //#path-suffix
    final Route route = 
        route(
            pathPrefix("start", () -> 
                route(
                    pathSuffix("end", () -> completeWithUnmatchedPath.get()),
                    pathSuffix(segment("foo").slash("bar").concat("baz"), () -> 
                        completeWithUnmatchedPath.get())
                )
            )
        );
    // tests:
    testRoute(route).run(HttpRequest.GET("/start/middle/end")).assertEntity("/middle/");
    testRoute(route).run(HttpRequest.GET("/start/something/barbaz/foo")).assertEntity("/something/");
    //#path-suffix
  }

  @Test
  public void testPathSuffixTest() {
    //#path-suffix-test
    final Route route = 
        route(
            pathSuffixTest(slash(), () -> complete("slashed")),
            complete("unslashed")
        );
    // tests:
    testRoute(route).run(HttpRequest.GET("/foo/")).assertEntity("slashed");
    testRoute(route).run(HttpRequest.GET("/foo")).assertEntity("unslashed");
    //#path-suffix-test
  }

  @Test
  public void testRawPathPrefix() {
    //#raw-path-prefix
    final Route route = 
        route(
            pathPrefix("foo", () ->
                route(
                      rawPathPrefix("bar", () -> completeWithUnmatchedPath.get()),
                      rawPathPrefix("doo", () -> completeWithUnmatchedPath.get())
                )
            )
        );
    // tests:
    testRoute(route).run(HttpRequest.GET("/foobar/baz")).assertEntity("/baz");
    testRoute(route).run(HttpRequest.GET("/foodoo/baz")).assertEntity("/baz");
    //#raw-path-prefix
  }

  @Test
  public void testRawPathPrefixTest() {
    //#raw-path-prefix-test
    final Route route = 
        route(
            pathPrefix("foo", () ->
                rawPathPrefixTest("bar", () -> completeWithUnmatchedPath.get())
            )
        );
    // tests:
    testRoute(route).run(HttpRequest.GET("/foobar")).assertEntity("bar");
    testRoute(route).run(HttpRequest.GET("/foobaz")).assertStatusCode(StatusCodes.NOT_FOUND);
    //#raw-path-prefix-test
  }

  @Test
  public void testRedirectToNoTrailingSlashIfMissing() {
    //#redirect-notrailing-slash-missing
    final Route route = 
        redirectToTrailingSlashIfMissing(
            StatusCodes.MOVED_PERMANENTLY, () ->
            route(
                path(segment("foo").slash(), () -> complete("OK")),
                path(segment("bad-1"), () -> 
                    // MISTAKE!
                    // Missing .slash() in path, causes this path to never match,
                    // because it is inside a `redirectToTrailingSlashIfMissing`
                    complete(StatusCodes.NOT_IMPLEMENTED)
                ),
                path(segment("bad-2/"), () ->
                    // MISTAKE!
                    // / should be explicit with `.slash()` and not *in* the path element
                    // So it should be: segment("bad-2").slash()
                    complete(StatusCodes.NOT_IMPLEMENTED)
                )
            )
        );
    // tests:
    testRoute(route).run(HttpRequest.GET("/foo"))
        .assertStatusCode(StatusCodes.MOVED_PERMANENTLY)
        .assertEntity("This and all future requests should be directed to " +
          "<a href=\"http://example.com/foo/\">this URI</a>.");

    testRoute(route).run(HttpRequest.GET("/foo/"))
        .assertStatusCode(StatusCodes.OK)
        .assertEntity("OK");

    testRoute(route).run(HttpRequest.GET("/bad-1/"))
        .assertStatusCode(StatusCodes.NOT_FOUND);
    //#redirect-notrailing-slash-missing
  }

  @Test
  public void testRedirectToNoTrailingSlashIfPresent() {
    //#redirect-notrailing-slash-present
    final Route route = 
        redirectToNoTrailingSlashIfPresent(
            StatusCodes.MOVED_PERMANENTLY, () ->
            route(
                path("foo", () -> complete("OK")),
                path(segment("bad").slash(), () -> 
                    // MISTAKE!
                    // Since inside a `redirectToNoTrailingSlashIfPresent` directive
                    // the matched path here will never contain a trailing slash,
                    // thus this path will never match.
                    //
                    // It should be `path("bad")` instead.
                     complete(StatusCodes.NOT_IMPLEMENTED)
                )
            )
        );
    // tests:
    testRoute(route).run(HttpRequest.GET("/foo/"))
        .assertStatusCode(StatusCodes.MOVED_PERMANENTLY)
        .assertEntity("This and all future requests should be directed to " +
          "<a href=\"http://example.com/foo\">this URI</a>.");

    testRoute(route).run(HttpRequest.GET("/foo"))
        .assertStatusCode(StatusCodes.OK)
        .assertEntity("OK");

    testRoute(route).run(HttpRequest.GET("/bad"))
        .assertStatusCode(StatusCodes.NOT_FOUND);
    //#redirect-notrailing-slash-present
  }

  @Test
  public void testIgnoreTrailingSlash() {
    //#ignoreTrailingSlash
    final Route route = ignoreTrailingSlash(() ->
      route(
        path("foo", () ->
          // Thanks to `ignoreTrailingSlash` it will serve both `/foo` and `/foo/`.
          complete("OK")),
        path(PathMatchers.segment("bar").slash(), () ->
          // Thanks to `ignoreTrailingSlash` it will serve both `/bar` and `/bar/`.
          complete("OK"))
      )
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/foo"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("OK");
    testRoute(route).run(HttpRequest.GET("/foo/"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("OK");

    testRoute(route).run(HttpRequest.GET("/bar"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("OK");
    testRoute(route).run(HttpRequest.GET("/bar/"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("OK");
    //#ignoreTrailingSlash
  }
}
