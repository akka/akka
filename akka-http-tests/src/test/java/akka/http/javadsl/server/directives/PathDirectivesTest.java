/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives;

import static akka.http.javadsl.server.PathMatcher.segment;
import static akka.http.javadsl.server.PathMatcher.separateOnSlashes;
import static akka.http.javadsl.server.PathMatchers.*;

import org.junit.Test;

import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.Host;
import akka.http.javadsl.server.PathMatchers;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import akka.http.scaladsl.model.HttpRequest;

public class PathDirectivesTest extends JUnitRouteTest {
    @Test
    public void testPathPrefixAndPath() {
        TestRoute route = testRoute(
            pathPrefix("pet", () -> route(
                path("cat", () -> complete("The cat!")),
                path("dog", () -> complete("The dog!")),
                pathSingleSlash(() -> complete("Here are only pets."))
            ))
        );

        route.run(HttpRequest.GET("/pet/"))
            .assertEntity("Here are only pets.");

        route.run(HttpRequest.GET("/pet")) // missing trailing slash
            .assertStatusCode(404);

        route.run(HttpRequest.GET("/pet/cat"))
            .assertEntity("The cat!");

        route.run(HttpRequest.GET("/pet/dog"))
            .assertEntity("The dog!");
    }

    @Test
    public void testRawPathPrefix() {
        TestRoute route1 = testRoute (
            rawPathPrefix(separateOnSlashes("/pet//cat"), () -> complete("The cat!"))
        );

        route1.run(HttpRequest.GET("/pet//cat"))
            .assertEntity("The cat!");

        // any suffix allowed
        route1.run(HttpRequest.GET("/pet//cat/abcdefg"))
            .assertEntity("The cat!");

        TestRoute route2 = testRoute (
            rawPathPrefix(separateOnSlashes("/pet//cat"), () -> 
                pathEnd(() -> complete("The cat!"))
            )
        );

        route2.run(HttpRequest.GET("/pet//cat"))
            .assertEntity("The cat!");

        route2.run(HttpRequest.GET("/pet//cat/abcdefg"))
            .assertStatusCode(404);
    }

    @Test
    public void testSegment() {
        TestRoute route =
            testRoute(
                pathPrefix("hey", () ->
                    path(name -> 
                        complete(name)
                    )
                )
            );

        route.run(HttpRequest.GET("/hey/jude"))
            .assertEntity("jude");
    }

    @Test
    public void testPathEnd() {
        TestRoute route =
            testRoute(
                pathPrefix("test", () -> route(
                    pathEnd(() -> complete("end")),
                    path("abc", () -> complete("abc"))
                ))
            );

        route.run(HttpRequest.GET("/test"))
            .assertEntity("end");

        route.run(HttpRequest.GET("/test/abc"))
            .assertEntity("abc");

        route.run(HttpRequest.GET("/xyz"))
            .assertStatusCode(404);
    }
    @Test
    public void testSingleSlash() {
        TestRoute route =
            testRoute(
                pathPrefix("test", () -> 
                    pathSingleSlash(() -> complete("Ok"))
                )
            );

        route.run(HttpRequest.GET("/test/"))
            .assertEntity("Ok");

        route.run(HttpRequest.GET("/test"))
            .assertStatusCode(404);
    }

    @Test
    public void testPathEndOrSingleSlash() {
        TestRoute route =
            testRoute(
                pathPrefix("test", () ->
                    pathEndOrSingleSlash(() -> complete("Ok"))
                )
            );

        route.run(HttpRequest.GET("/test"))
                .assertEntity("Ok");

        route.run(HttpRequest.GET("/test/"))
                .assertEntity("Ok");

        route.run(HttpRequest.GET("/abc"))
                .assertStatusCode(404);
    }

    @Test
    public void testRawPathPrefixTest() {
        TestRoute route =
            testRoute(
                rawPathPrefixTest(segment("/abc"), () ->
                    extractUnmatchedPath(s -> complete(s))
                )
            );

        route.run(HttpRequest.GET("/abc"))
            .assertEntity("/abc");

        route.run(HttpRequest.GET("/abc/def"))
            .assertEntity("/abc/def");

        route.run(HttpRequest.GET("/abcd/ef"))
                .assertEntity("/abcd/ef");

        route.run(HttpRequest.GET("/xyz/def"))
            .assertStatusCode(404);
    }
    @Test
    public void testPathPrefixTest() {
        TestRoute route =
            testRoute(
                pathPrefixTest("abc", () -> extractUnmatchedPath(s -> complete(s)))
            );

        route.run(HttpRequest.GET("/abc"))
            .assertEntity("/abc");

        route.run(HttpRequest.GET("/abc/def"))
            .assertEntity("/abc/def");

        route.run(HttpRequest.GET("/abcd/ef"))
            .assertEntity("/abcd/ef");

        route.run(HttpRequest.GET("/xyz/def"))
            .assertStatusCode(404);
    }
    @Test
    public void testPathSuffix() {
        TestRoute route =
            testRoute(
                pathSuffix(PathMatchers.SLASH.concat(segment("abc")), () -> extractUnmatchedPath(s -> complete(s)))
            );

        route.run(HttpRequest.GET("/test/abc/"))
            .assertEntity("/test/");

        route.run(HttpRequest.GET("/abc/"))
            .assertEntity("/");

        route.run(HttpRequest.GET("/abc/def"))
            .assertStatusCode(404);

        route.run(HttpRequest.GET("/abc"))
            .assertStatusCode(404);
    }
    @Test
    public void testPathSuffixTest() {
        TestRoute route =
            testRoute(
                pathSuffixTest("abc", () -> extractUnmatchedPath(s -> complete(s)))
            );

        route.run(HttpRequest.GET("/test/abc"))
            .assertEntity("/test/abc");

        route.run(HttpRequest.GET("/abc"))
            .assertEntity("/abc");

        route.run(HttpRequest.GET("/abc/def"))
            .assertStatusCode(404);
    }

    @Test
    public void testIntegerMatcher() {
        TestRoute route =
            testRoute(
                path(segment("age").slash(INTEGER_SEGMENT), value -> complete(value.toString()))
            );

        route.run(HttpRequest.GET("/age/38"))
            .assertEntity("38");

        route.run(HttpRequest.GET("/age/abc"))
            .assertStatusCode(404);
    }
    @Test
    public void testTwoVals() {
        // tests that `x` and `y` have different identities which is important for
        // retrieving the values

        TestRoute route =
            testRoute(
                path(segment("multiply").slash(INTEGER_SEGMENT).slash("with").slash(INTEGER_SEGMENT), (x, y) ->
                    complete(String.format("%d * %d = %d", x, y, x * y))
                )
            );

        route.run(HttpRequest.GET("/multiply/3/with/6"))
            .assertEntity("3 * 6 = 18");
    }

    @Test
    public void testHexIntegerMatcher() {
        TestRoute route =
            testRoute(
                path(segment("color").slash(HEX_INTEGER_SEGMENT), color -> complete(color.toString()))
            );

        route.run(HttpRequest.GET("/color/a0c2ef"))
            .assertEntity(Integer.toString(0xa0c2ef));
    }

    @Test
    public void testLongMatcher() {
        TestRoute route =
            testRoute(
                path(segment("bigage").slash(LONG_SEGMENT), bigAge -> complete(bigAge.toString()))
            );

        route.run(HttpRequest.GET("/bigage/12345678901"))
            .assertEntity("12345678901");
    }

    @Test
    public void testHexLongMatcher() {
        TestRoute route =
            testRoute(
                path(segment("code").slash(HEX_LONG_SEGMENT), code -> complete(code.toString()))
            );

        route.run(HttpRequest.GET("/code/a0b1c2d3e4f5"))
            .assertEntity(Long.toString(0xa0b1c2d3e4f5L));
    }

    @Test
    public void testRestMatcher() {
        TestRoute route =
            testRoute(
                path(REMAINING, remainingPath -> complete(remainingPath))
            );

        route.run(HttpRequest.GET("/pets/afdaoisd/asda/sfasfasf/asf"))
            .assertEntity("afdaoisd/asda/sfasfasf/asf");
    }

    @Test
    public void testUUIDMatcher() {
        TestRoute route =
            testRoute(
                path(segment("by-uuid").slash(UUID_SEGMENT), uuid -> complete(uuid.toString()))
            );

        route.run(HttpRequest.GET("/by-uuid/6ba7b811-9dad-11d1-80b4-00c04fd430c8"))
            .assertEntity("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
    }

    @Test
    public void testSegmentsMatcher() {
        TestRoute route =
            testRoute(
                    path(segment("pets").slash(SEGMENTS), segments -> complete(segments.toString()))
            );

        route.run(HttpRequest.GET("/pets/cat/dog"))
            .assertEntity("[cat, dog]");
    }

    @Test
    public void testRedirectToTrailingSlashIfMissing() {
        TestRoute route =
            testRoute(
                redirectToTrailingSlashIfMissing(StatusCodes.FOUND, () -> complete("Ok"))
            );

        route.run(HttpRequest.GET("/home").addHeader(Host.create("example.com")))
            .assertStatusCode(302)
            .assertHeaderExists("Location", "http://example.com/home/");

        route.run(HttpRequest.GET("/home/"))
            .assertStatusCode(200)
            .assertEntity("Ok");
    }

    @Test
    public void testRedirectToNoTrailingSlashIfPresent() {
        TestRoute route =
            testRoute(
                redirectToNoTrailingSlashIfPresent(StatusCodes.FOUND, () -> complete("Ok"))
            );

        route.run(HttpRequest.GET("/home/").addHeader(Host.create("example.com")))
            .assertStatusCode(302)
            .assertHeaderExists("Location", "http://example.com/home");

        route.run(HttpRequest.GET("/home"))
            .assertStatusCode(200)
            .assertEntity("Ok");
    }
}
