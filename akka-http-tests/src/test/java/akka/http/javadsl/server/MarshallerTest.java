/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server;


import java.util.function.Function;

import org.junit.Test;

import akka.http.javadsl.model.HttpCharsets;
import akka.http.javadsl.model.HttpEntities;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.MediaTypes;
import akka.http.javadsl.model.RequestEntity;
import akka.http.javadsl.model.headers.Accept;
import akka.http.javadsl.model.headers.AcceptCharset;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import akka.util.ByteString;

public class MarshallerTest extends JUnitRouteTest {

    @Test
    public void testCustomToStringMarshaller() {
        final Marshaller<Integer, RequestEntity> numberAsNameMarshaller =
            Marshaller.wrapEntity((Integer param) -> {
                    switch(param) {
                        case 0: return "null";
                        case 1: return "eins";
                        case 2: return "zwei";
                        case 3: return "drei";
                        case 4: return "vier";
                        case 5: return "fünf";
                        default: return "wat?";
                    }
                }, Marshaller.stringToEntity(), MediaTypes.TEXT_X_SPEECH);

        
        final Function<Integer,Route> nummerHandler = integer -> completeOK(integer, numberAsNameMarshaller);

        TestRoute route =
            testRoute(
                get(() ->
                    path("nummer", () ->
                        param(StringUnmarshallers.INTEGER, "n", nummerHandler)
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
        final Marshaller<Integer, RequestEntity> numberAsJsonListMarshaller =
                Marshaller.wrapEntity((Integer param) -> {
                        switch(param) {
                            case 1: return ByteString.fromString("[1]");
                            case 5: return ByteString.fromString("[1,2,3,4,5]");
                            default: return ByteString.fromString("[]");
                        }
                    }, Marshaller.byteStringToEntity(), MediaTypes.APPLICATION_JSON);

        final Function<Integer,Route> nummerHandler = integer -> completeOK(integer, numberAsJsonListMarshaller);

        TestRoute route =
                testRoute(
                    get(() ->
                        path("nummer", () ->
                            param(StringUnmarshallers.INTEGER, "n", nummerHandler)
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
        final Marshaller<Integer, RequestEntity> numberAsJsonListMarshaller =
            Marshaller.opaque((Integer param) -> {
                        switch(param) {
                            case 1: return HttpEntities.create(MediaTypes.APPLICATION_JSON.toContentType(), "[1]");
                            case 5: return HttpEntities.create(MediaTypes.APPLICATION_JSON.toContentType(), "[1,2,3,4,5]");
                            default: return HttpEntities.create(MediaTypes.APPLICATION_JSON.toContentType(), "[]");
                        }
                    });

        final Function<Integer,Route> nummerHandler = integer -> completeOK(integer, numberAsJsonListMarshaller);

        TestRoute route =
            testRoute(
                get(() ->
                    path("nummer", () ->
                        param(StringUnmarshallers.INTEGER, "n", nummerHandler)
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
        final Marshaller<Integer,HttpResponse> numberAsJsonListMarshaller =
            Marshaller.opaque((Integer param) -> {
                    switch(param) {
                        case 1: return HttpResponse.create().withEntity(MediaTypes.APPLICATION_JSON.toContentType(), "[1]");
                        case 5: return HttpResponse.create().withEntity(MediaTypes.APPLICATION_JSON.toContentType(), "[1,2,3,4,5]");
                        default: return HttpResponse.create().withStatus(404);
                    }
                });
            
        final Function<Integer,Route> nummerHandler = integer -> complete(integer, numberAsJsonListMarshaller);

        TestRoute route =
            testRoute(
                get(() ->
                    path("nummer", () ->
                        param(StringUnmarshallers.INTEGER, "n", nummerHandler)
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
