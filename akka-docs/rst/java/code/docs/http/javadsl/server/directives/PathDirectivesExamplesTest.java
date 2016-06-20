/*
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server.directives;

import java.util.Arrays;
import java.util.regex.Pattern;

import org.junit.Test;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import static akka.http.javadsl.server.PathMatchers.segment;
import static akka.http.javadsl.server.PathMatchers.segments;
import static akka.http.javadsl.server.PathMatchers.integerSegment;
import java.util.function.Supplier;
import akka.http.javadsl.server.directives.RouteAdapter;

public class PathDirectivesExamplesTest extends JUnitRouteTest {

    //http://doc.akka.io/docs/akka/2.4.5/java/http/server-side-https-support.html
    //http://doc.akka.io/docs/akka/2.4.6/java/http/routing-dsl/directives/path-directives.html#path-directives-java
    //https://github.com/akka/akka/blob/acb71ac4e53d536fcba0ffe0fb0a48c09a63b108/akka-http/src/main/scala/akka/http/javadsl/server/directives/PathDirectives.scala

  //# pathPrefixTest-, rawPathPrefix-, rawPathPrefixTest-, pathSuffix-, pathSuffixTest-
  Supplier<RouteAdapter> completeWithUnmatchedPath = ()->
    extractUnmatchedPath((path) -> complete(path.toString()));

  @Test
  public void testBasicExamples() {
    path("test", () -> complete(StatusCodes.OK));
     
    // matches "/test", as well
    path(segment("test"), () -> complete(StatusCodes.OK));

  }

  @Test
  public void testPathExample() {
    final Route route = 
        route(
            path("foo", () -> complete("/foo")),
            path(segment("foo").slash("bar"), () -> complete("/foo/bar")),
            pathPrefix("ball", ()-> 
                route(
                    pathEnd(()-> complete("/ball")),
                    path(integerSegment(), (i)-> complete((i % 2 == 0) ? "even ball" : "odd ball"))
                )
            )
        );

    // tests:
    testRoute(route).run(HttpRequest.GET("/")).assertStatusCode(StatusCodes.NOT_FOUND);
    testRoute(route).run(HttpRequest.GET("/foo")).assertEntity("/foo");
    testRoute(route).run(HttpRequest.GET("/foo/bar")).assertEntity("/foo/bar");
    testRoute(route).run(HttpRequest.GET("/ball/1337")).assertEntity("odd ball");
  }

  @Test
  public void testPathEnd() {
    final Route route = 
        route(
            pathPrefix("foo", ()-> 
                route(
                    pathEnd(()-> complete("/foo")),
                    path("bar", () -> complete("/foo/bar"))
                )
            )
        );

    // tests:
    testRoute(route).run(HttpRequest.GET("/foo")).assertEntity("/foo");
    testRoute(route).run(HttpRequest.GET("/foo/")).assertStatusCode(StatusCodes.NOT_FOUND);
    testRoute(route).run(HttpRequest.GET("/foo/bar")).assertEntity("/foo/bar");
  }

  @Test
  public void testPathEndOrSingleSlash() {
    final Route route = 
        route(
            pathPrefix("foo", ()-> 
                route(
                    pathEndOrSingleSlash(()-> complete("/foo")),
                    path("bar", () -> complete("/foo/bar"))
                )
            )
        );
    // tests:
    testRoute(route).run(HttpRequest.GET("/foo")).assertEntity("/foo");
    testRoute(route).run(HttpRequest.GET("/foo/")).assertEntity("/foo");
    testRoute(route).run(HttpRequest.GET("/foo/bar")).assertEntity("/foo/bar");
  }

  @Test
  public void testPathPrefix() {
    final Route route = 
        route(
            pathPrefix("ball", ()-> 
                route(
                    pathEnd(()-> complete("/ball")),
                    path(integerSegment(), (i)-> complete((i % 2 == 0) ? "even ball" : "odd ball"))
                )
            )
        );
    // tests:
    testRoute(route).run(HttpRequest.GET("/")).assertStatusCode(StatusCodes.NOT_FOUND);
    testRoute(route).run(HttpRequest.GET("/ball")).assertEntity("/ball");
    testRoute(route).run(HttpRequest.GET("/ball/1337")).assertEntity("odd ball");
  }

  @Test
  public void testPathPrefixTest() {
    // TODO: confirm this implementation with the akka guys!
    final Route route = 
        route(
            pathPrefixTest("foo", ()-> pathPrefix("foo", ()-> completeWithUnmatchedPath.get())),
            pathPrefixTest("bar", ()-> pathPrefix("bar", ()-> completeWithUnmatchedPath.get()))
        );
    // tests:
    testRoute(route).run(HttpRequest.GET("/foo/doo")).assertEntity("/doo");
    testRoute(route).run(HttpRequest.GET("/bar/yes")).assertEntity("/yes");
  }

  @Test
  public void testPathSingleSlash() {
    final Route route = 
        route(
            pathSingleSlash(()-> complete("root")),
            pathPrefix("ball", ()-> 
                route(
                    pathSingleSlash(()-> complete("/ball/")),
                    path(integerSegment(), (i)-> complete((i % 2 == 0) ? "even ball" : "odd ball"))
                )
            )
        );
    // tests:
    testRoute(route).run(HttpRequest.GET("/")).assertEntity("root");
    testRoute(route).run(HttpRequest.GET("/ball")).assertStatusCode(StatusCodes.NOT_FOUND);
    testRoute(route).run(HttpRequest.GET("/ball/")).assertEntity("/ball/");
    testRoute(route).run(HttpRequest.GET("/ball/1337")).assertEntity("odd ball");
  }

  @Test
  public void testPathSuffix() {
    final Route route = 
        route(
            pathPrefix("start", ()-> 
                route(
                    pathSuffix("end", ()-> completeWithUnmatchedPath.get()),
                    pathSuffix(segment("foo").slash("bar"), ()-> completeWithUnmatchedPath.get()),
                    pathSuffix(segment("foo").slash("baz"), ()-> completeWithUnmatchedPath.get())
                )
            )
        );
    // tests:
    
  }

}
