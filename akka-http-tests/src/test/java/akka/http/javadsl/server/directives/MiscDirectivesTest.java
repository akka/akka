/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.Uri;
import akka.http.javadsl.server.RequestContext;
import akka.http.javadsl.server.RequestVal;
import akka.http.javadsl.server.RequestVals;
import akka.http.javadsl.server.values.Parameter;
import akka.http.javadsl.server.values.Parameters;
import akka.http.javadsl.server.values.PathMatchers;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import akka.japi.function.Function;
import akka.japi.function.Function2;
import org.junit.Test;

public class MiscDirectivesTest extends JUnitRouteTest {
    static Parameter<String> stringParam = Parameters.stringValue("stringParam");

    static Function<String, Boolean> isShort =
        new Function<String, Boolean>() {
            @Override
            public Boolean apply(String str) throws Exception {
                return str.length() < 5;
            }
        };

    @Test
    public void testValidateRequestContext() {
        Function<RequestContext, Boolean> hasShortPath =
            new Function<RequestContext, Boolean>() {
                @Override
                public Boolean apply(RequestContext ctx) throws Exception {
                    return ctx.request().getUri().path().toString().length() < 5;
                }
            };

        TestRoute route = testRoute(validate(hasShortPath, "Path too long!", complete("OK!")));

        route
            .run(HttpRequest.create().withUri(Uri.create("/abc")))
            .assertStatusCode(200)
            .assertEntity("OK!");

        route
            .run(HttpRequest.create().withUri(Uri.create("/abcdefghijkl")))
            .assertStatusCode(400)
            .assertEntity("Path too long!");
    }
    @Test
    public void testValidateOneStandaloneRequestVal() {
        TestRoute route = testRoute(validate(stringParam, isShort, "stringParam too long!", complete("OK!")));

        route
            .run(HttpRequest.GET("/?stringParam=abcd"))
            .assertStatusCode(200)
            .assertEntity("OK!");

        route
            .run(HttpRequest.GET("/?stringParam=abcdefg"))
            .assertStatusCode(400)
            .assertEntity("stringParam too long!");
    }
    @Test
    public void testValidateOnePathMatcherRequestVal() {
        RequestVal<String> nameSegment = PathMatchers.segment();

        TestRoute route = testRoute(
            path("people", nameSegment, "address").route(
                validate(nameSegment, isShort, "Segment too long!", complete("OK!"))
            )
        );

        route
            .run(HttpRequest.GET("/people/john/address"))
            .assertStatusCode(200)
            .assertEntity("OK!");

        route
            .run(HttpRequest.GET("/people/hermanbaker/address"))
            .assertStatusCode(400)
            .assertEntity("Segment too long!");
    }
    @Test
    public void testValidateTwoRequestVals() {
        Function2<String, String, Boolean> stringParamEqualsHostName =
                new Function2<String, String, Boolean>() {
                    @Override
                    public Boolean apply(String stringParam, String hostName) throws Exception {
                        return stringParam.equals(hostName);
                    }
                };

        TestRoute route =
            testRoute(
                validate(stringParam, RequestVals.host(), stringParamEqualsHostName, "stringParam must equal hostName!",
                    complete("OK!")));

        route
            .run(HttpRequest.GET("http://example.org/?stringParam=example.org"))
            .assertStatusCode(200)
            .assertEntity("OK!");

        route
            .run(HttpRequest.GET("http://blubber.org/?stringParam=example.org"))
            .assertStatusCode(400)
            .assertEntity("stringParam must equal hostName!");

        route
            .run(HttpRequest.GET("http://blubber.org/"))
            .assertStatusCode(404);
    }
}
