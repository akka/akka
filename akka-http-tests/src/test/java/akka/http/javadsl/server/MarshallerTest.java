/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server;

import akka.http.javadsl.model.*;
import akka.http.javadsl.model.headers.Accept;
import akka.http.javadsl.model.headers.AcceptCharset;
import akka.http.javadsl.server.values.Parameters;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import akka.japi.function.Function;
import akka.util.ByteString;
import org.junit.Test;

public class MarshallerTest extends JUnitRouteTest {
    RequestVal<Integer> n = Parameters.intValue("n");

    @Test
    public void testCustomToStringMarshaller() {
        final Marshaller<Integer> numberAsNameMarshaller =
            Marshallers.toEntityString(MediaTypes.TEXT_X_SPEECH, new Function<Integer, String>() {
                @Override
                public String apply(Integer param) throws Exception {
                    switch(param) {
                        case 0: return "null";
                        case 1: return "eins";
                        case 2: return "zwei";
                        case 3: return "drei";
                        case 4: return "vier";
                        case 5: return "fünf";
                        default: return "wat?";
                    }
                }
            });

        Handler1<Integer> nummerHandler = new Handler1<Integer>() {
            @Override
            public RouteResult apply(RequestContext ctx, Integer integer) {
                return ctx.completeAs(numberAsNameMarshaller, integer);
            }
        };

        TestRoute route =
            testRoute(
                get(
                    path("nummer").route(
                        handleWith1(n, nummerHandler)
                    )
                )
            );

        route.run(HttpRequest.GET("/nummer?n=1"))
            .assertStatusCode(200)
            .assertMediaType(MediaTypes.TEXT_X_SPEECH)
            .assertEntity("eins");

        route.run(HttpRequest.GET("/nummer?n=6"))
            .assertStatusCode(200)
            .assertMediaType(MediaTypes.TEXT_X_SPEECH)
            .assertEntity("wat?");

        route.run(HttpRequest.GET("/nummer?n=5"))
            .assertStatusCode(200)
            .assertEntityBytes(ByteString.fromString("fünf", "utf8"));

        route.run(
            HttpRequest.GET("/nummer?n=5")
                .addHeader(AcceptCharset.create(HttpCharsets.ISO_8859_1.toRange())))
            .assertStatusCode(200)
            .assertEntityBytes(ByteString.fromString("fünf", "ISO-8859-1"));
    }

    @Test
    public void testCustomToByteStringMarshaller() {
        final Marshaller<Integer> numberAsJsonListMarshaller =
                Marshallers.toEntityByteString(MediaTypes.APPLICATION_JSON.toContentType(), new Function<Integer, ByteString>() {
                    @Override
                    public ByteString apply(Integer param) throws Exception {
                        switch(param) {
                            case 1: return ByteString.fromString("[1]");
                            case 5: return ByteString.fromString("[1,2,3,4,5]");
                            default: return ByteString.fromString("[]");
                        }
                    }
                });

        Handler1<Integer> nummerHandler = new Handler1<Integer>() {
            @Override
            public RouteResult apply(RequestContext ctx, Integer integer) {
                return ctx.completeAs(numberAsJsonListMarshaller, integer);
            }
        };

        TestRoute route =
                testRoute(
                        get(
                                path("nummer").route(
                                        handleWith1(n, nummerHandler)
                                )
                        )
                );

        route.run(HttpRequest.GET("/nummer?n=1"))
            .assertStatusCode(200)
            .assertMediaType(MediaTypes.APPLICATION_JSON)
            .assertEntity("[1]");

        route.run(HttpRequest.GET("/nummer?n=5"))
            .assertStatusCode(200)
            .assertEntity("[1,2,3,4,5]");

        route.run(
            HttpRequest.GET("/nummer?n=5").addHeader(Accept.create(MediaTypes.TEXT_PLAIN.toRange())))
            .assertStatusCode(406);
    }

    @Test
    public void testCustomToEntityMarshaller() {
        final Marshaller<Integer> numberAsJsonListMarshaller =
            Marshallers.toEntity(MediaTypes.APPLICATION_JSON.toContentType(), new Function<Integer, ResponseEntity>() {
                    @Override
                    public ResponseEntity apply(Integer param) throws Exception {
                        switch(param) {
                            case 1: return HttpEntities.create(MediaTypes.APPLICATION_JSON.toContentType(), "[1]");
                            case 5: return HttpEntities.create(MediaTypes.APPLICATION_JSON.toContentType(), "[1,2,3,4,5]");
                            default: return HttpEntities.create(MediaTypes.APPLICATION_JSON.toContentType(), "[]");
                        }
                    }
            });

        Handler1<Integer> nummerHandler = new Handler1<Integer>() {
            @Override
            public RouteResult apply(RequestContext ctx, Integer integer) {
                return ctx.completeAs(numberAsJsonListMarshaller, integer);
            }
        };

        TestRoute route =
            testRoute(
                get(
                    path("nummer").route(
                        handleWith1(n, nummerHandler)
                    )
                )
            );

        route.run(HttpRequest.GET("/nummer?n=1"))
            .assertStatusCode(200)
            .assertMediaType(MediaTypes.APPLICATION_JSON)
            .assertEntity("[1]");

        route.run(HttpRequest.GET("/nummer?n=5"))
            .assertStatusCode(200)
            .assertEntity("[1,2,3,4,5]");

        route.run(
            HttpRequest.GET("/nummer?n=5").addHeader(Accept.create(MediaTypes.TEXT_PLAIN.toRange())))
            .assertStatusCode(406);
    }

    @Test
    public void testCustomToResponseMarshaller() {
        final Marshaller<Integer> numberAsJsonListMarshaller =
            Marshallers.toResponse(MediaTypes.APPLICATION_JSON.toContentType(), new Function<Integer, HttpResponse>() {
                @Override
                public HttpResponse apply(Integer param) throws Exception {
                    switch(param) {
                        case 1: return HttpResponse.create().withEntity(MediaTypes.APPLICATION_JSON.toContentType(), "[1]");
                        case 5: return HttpResponse.create().withEntity(MediaTypes.APPLICATION_JSON.toContentType(), "[1,2,3,4,5]");
                        default: return HttpResponse.create().withStatus(404);
                    }
                }
            });

        Handler1<Integer> nummerHandler = new Handler1<Integer>() {
            @Override
            public RouteResult apply(RequestContext ctx, Integer integer) {
                return ctx.completeAs(numberAsJsonListMarshaller, integer);
            }
        };

        TestRoute route =
            testRoute(
                get(
                    path("nummer").route(
                        handleWith1(n, nummerHandler)
                    )
                )
            );

        route.run(HttpRequest.GET("/nummer?n=1"))
            .assertStatusCode(200)
            .assertMediaType(MediaTypes.APPLICATION_JSON)
            .assertEntity("[1]");

        route.run(HttpRequest.GET("/nummer?n=5"))
            .assertStatusCode(200)
            .assertEntity("[1,2,3,4,5]");

        route.run(HttpRequest.GET("/nummer?n=6"))
            .assertStatusCode(404);

        route.run(HttpRequest.GET("/nummer?n=5").addHeader(Accept.create(MediaTypes.TEXT_PLAIN.toRange())))
            .assertStatusCode(406);
    }
}
