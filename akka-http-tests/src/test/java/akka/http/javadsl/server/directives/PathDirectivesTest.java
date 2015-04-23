/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server.directives;

import org.junit.Test;

import java.util.List;
import java.util.UUID;

import akka.http.javadsl.server.*;
import akka.http.javadsl.testkit.*;
import akka.http.scaladsl.model.HttpRequest;

import static akka.http.javadsl.server.Directives.*;

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
                path("hey", name).route(toStringEcho(name))
            );

        route.run(HttpRequest.GET("/hey/jude"))
            .assertEntity("jude");
    }

    @Test
    public void testSingleSlash() {
        TestRoute route =
            testRoute(
                pathSingleSlash().route(complete("Ok"))
            );

        route.run(HttpRequest.GET("/"))
            .assertEntity("Ok");

        route.run(HttpRequest.GET("/abc"))
            .assertStatusCode(404);
    }

    @Test
    public void testIntegerMatcher() {
        PathMatcher<Integer> age = PathMatchers.integerNumber();

        TestRoute route =
            testRoute(
                path("age", age).route(toStringEcho(age))
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
        PathMatcher<Integer> x = PathMatchers.integerNumber();
        PathMatcher<Integer> y = PathMatchers.integerNumber();

        TestRoute route =
            testRoute(
                path("multiply", x, y).route(
                    handleWith(x, y, new Handler2<Integer, Integer>() {
                        @Override
                        public RouteResult handle(RequestContext ctx, Integer x, Integer y) {
                            return ctx.complete(String.format("%d * %d = %d", x, y, x * y));
                        }
                    })
                )
            );

        route.run(HttpRequest.GET("/multiply/3/6"))
            .assertEntity("3 * 6 = 18");
    }

    @Test
    public void testHexIntegerMatcher() {
        PathMatcher<Integer> color = PathMatchers.hexIntegerNumber();

        TestRoute route =
            testRoute(
                path("color", color).route(toStringEcho(color))
            );

        route.run(HttpRequest.GET("/color/a0c2ef"))
            .assertEntity(Integer.toString(0xa0c2ef));
    }

    @Test
    public void testLongMatcher() {
        PathMatcher<Long> bigAge = PathMatchers.longNumber();

        TestRoute route =
            testRoute(
                path("bigage", bigAge).route(toStringEcho(bigAge))
            );

        route.run(HttpRequest.GET("/bigage/12345678901"))
            .assertEntity("12345678901");
    }

    @Test
    public void testHexLongMatcher() {
        PathMatcher<Long> code = PathMatchers.hexLongNumber();

        TestRoute route =
            testRoute(
                path("code", code).route(toStringEcho(code))
            );

        route.run(HttpRequest.GET("/code/a0b1c2d3e4f5"))
            .assertEntity(Long.toString(0xa0b1c2d3e4f5L));
    }

    @Test
    public void testRestMatcher() {
        PathMatcher<String> rest = PathMatchers.rest();

        TestRoute route =
            testRoute(
                path("pets", rest).route(toStringEcho(rest))
            );

        route.run(HttpRequest.GET("/pets/afdaoisd/asda/sfasfasf/asf"))
            .assertEntity("afdaoisd/asda/sfasfasf/asf");
    }

    @Test
    public void testUUIDMatcher() {
        PathMatcher<UUID> uuid = PathMatchers.uuid();

        TestRoute route =
            testRoute(
                path("by-uuid", uuid).route(toStringEcho(uuid))
            );

        route.run(HttpRequest.GET("/by-uuid/6ba7b811-9dad-11d1-80b4-00c04fd430c8"))
            .assertEntity("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
    }

    @Test
    public void testSegmentsMatcher() {
        PathMatcher<List<String>> segments = PathMatchers.segments();

        TestRoute route =
            testRoute(
                    path("pets", segments).route(toStringEcho(segments))
            );

        route.run(HttpRequest.GET("/pets/cat/dog"))
            .assertEntity("[cat, dog]");
    }

    private <T> Route toStringEcho(RequestVal<T> value) {
        return handleWith(value, new Handler1<T>() {
            @Override
            public RouteResult handle(RequestContext ctx, T t) {
                return ctx.complete(t.toString());
            }
        });
    }
}
