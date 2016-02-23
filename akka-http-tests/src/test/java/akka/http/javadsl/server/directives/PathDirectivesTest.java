/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives;

import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.Host;
import akka.http.javadsl.server.values.PathMatcher;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import akka.http.javadsl.server.*;
import akka.http.javadsl.server.values.*;
import akka.http.javadsl.testkit.*;
import akka.http.scaladsl.model.HttpRequest;

public class PathDirectivesTest extends JUnitRouteTest {
    @Test
    public void testPathPrefixAndPath() {
        TestRoute route =
            testRoute(
                pathPrefix("pet").route(
                    path("cat").route(complete("The cat!")),
                    path("dog").route(complete("The dog!")),
                    pathSingleSlash().route(complete("Here are only pets."))
                )
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
        TestRoute route1 =
            testRoute(
                rawPathPrefix(PathMatchers.SLASH(), "pet", PathMatchers.SLASH(), "", PathMatchers.SLASH(), "cat").route(
                        complete("The cat!")
                )
            );

        route1.run(HttpRequest.GET("/pet//cat"))
            .assertEntity("The cat!");

        // any suffix allowed
        route1.run(HttpRequest.GET("/pet//cat/abcdefg"))
            .assertEntity("The cat!");

        TestRoute route2 =
            testRoute(
                rawPathPrefix(PathMatchers.SLASH(), "pet", PathMatchers.SLASH(), "", PathMatchers.SLASH(), "cat", PathMatchers.END()).route(
                    complete("The cat!")
                )
            );

        route2.run(HttpRequest.GET("/pet//cat"))
            .assertEntity("The cat!");

        route2.run(HttpRequest.GET("/pet//cat/abcdefg"))
            .assertStatusCode(404);
    }

    @Test
    public void testSegment() {
        PathMatcher<String> name = PathMatchers.segment();

        TestRoute route =
            testRoute(
                path("hey", name).route(completeWithValueToString(name))
            );

        route.run(HttpRequest.GET("/hey/jude"))
            .assertEntity("jude");
    }

    @Test
    public void testPathEnd() {
        TestRoute route =
            testRoute(
                pathPrefix("test").route(
                    pathEnd().route(complete("end")),
                    path("abc").route(complete("abc"))
                )
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
                pathPrefix("test").route(
                    pathSingleSlash().route(complete("Ok"))
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
                pathPrefix("test").route(
                    pathEndOrSingleSlash().route(complete("Ok"))
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
                rawPathPrefixTest(PathMatchers.SLASH(), "abc").route(
                    completeWithValueToString(RequestVals.unmatchedPath())
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
                pathPrefixTest("abc").route(completeWithValueToString(RequestVals.unmatchedPath()))
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
                pathSuffix(PathMatchers.SLASH(), "abc").route(completeWithValueToString(RequestVals.unmatchedPath()))
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
                pathSuffixTest("abc").route(completeWithValueToString(RequestVals.unmatchedPath()))
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
        PathMatcher<Integer> age = PathMatchers.intValue();

        TestRoute route =
            testRoute(
                path("age", age).route(completeWithValueToString(age))
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
        PathMatcher<Integer> x = PathMatchers.intValue();
        PathMatcher<Integer> y = PathMatchers.intValue();

        TestRoute route =
            testRoute(
                path("multiply", x, "with", y).route(
                    handleWith2(x, y, new Handler2<Integer, Integer>() {
                        @Override
                        public RouteResult apply(RequestContext ctx, Integer x, Integer y) {
                            return ctx.complete(String.format("%d * %d = %d", x, y, x * y));
                        }
                    })
                )
            );

        route.run(HttpRequest.GET("/multiply/3/with/6"))
            .assertEntity("3 * 6 = 18");
    }

    @Test
    public void testHexIntegerMatcher() {
        PathMatcher<Integer> color = PathMatchers.hexIntValue();

        TestRoute route =
            testRoute(
                path("color", color).route(completeWithValueToString(color))
            );

        route.run(HttpRequest.GET("/color/a0c2ef"))
            .assertEntity(Integer.toString(0xa0c2ef));
    }

    @Test
    public void testLongMatcher() {
        PathMatcher<Long> bigAge = PathMatchers.longValue();

        TestRoute route =
            testRoute(
                path("bigage", bigAge).route(completeWithValueToString(bigAge))
            );

        route.run(HttpRequest.GET("/bigage/12345678901"))
            .assertEntity("12345678901");
    }

    @Test
    public void testHexLongMatcher() {
        PathMatcher<Long> code = PathMatchers.hexLongValue();

        TestRoute route =
            testRoute(
                path("code", code).route(completeWithValueToString(code))
            );

        route.run(HttpRequest.GET("/code/a0b1c2d3e4f5"))
            .assertEntity(Long.toString(0xa0b1c2d3e4f5L));
    }

    @Test
    public void testRestMatcher() {
        PathMatcher<String> rest = PathMatchers.rest();

        TestRoute route =
            testRoute(
                path("pets", rest).route(completeWithValueToString(rest))
            );

        route.run(HttpRequest.GET("/pets/afdaoisd/asda/sfasfasf/asf"))
            .assertEntity("afdaoisd/asda/sfasfasf/asf");
    }

    @Test
    public void testUUIDMatcher() {
        PathMatcher<UUID> uuid = PathMatchers.uuid();

        TestRoute route =
            testRoute(
                path("by-uuid", uuid).route(completeWithValueToString(uuid))
            );

        route.run(HttpRequest.GET("/by-uuid/6ba7b811-9dad-11d1-80b4-00c04fd430c8"))
            .assertEntity("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
    }

    @Test
    public void testSegmentsMatcher() {
        PathMatcher<List<String>> segments = PathMatchers.segments();

        TestRoute route =
            testRoute(
                    path("pets", segments).route(completeWithValueToString(segments))
            );

        route.run(HttpRequest.GET("/pets/cat/dog"))
            .assertEntity("[cat, dog]");
    }

    @Test
    public void testRedirectToTrailingSlashIfMissing() {
        TestRoute route =
            testRoute(
                redirectToTrailingSlashIfMissing(StatusCodes.FOUND, complete("Ok"))
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
                redirectToNoTrailingSlashIfPresent(StatusCodes.FOUND, complete("Ok"))
            );

        route.run(HttpRequest.GET("/home/").addHeader(Host.create("example.com")))
            .assertStatusCode(302)
            .assertHeaderExists("Location", "http://example.com/home");

        route.run(HttpRequest.GET("/home"))
            .assertStatusCode(200)
            .assertEntity("Ok");
    }
}
