/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server;


import java.util.function.Function;

import akka.http.javadsl.model.*;
import akka.http.javadsl.model.headers.*;
import org.junit.Test;

import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import akka.util.ByteString;

public class MarshallerTest extends JUnitRouteTest {

  @Test
  public void testCustomToStringMarshaller() {
    final Marshaller<Integer, RequestEntity> numberAsNameMarshaller =
      Marshaller.wrapEntity((Integer param) -> {
        switch (param) {
          case 0:
            return "null";
          case 1:
            return "eins";
          case 2:
            return "zwei";
          case 3:
            return "drei";
          case 4:
            return "vier";
          case 5:
            return "fünf";
          default:
            return "wat?";
        }
      }, Marshaller.stringToEntity(), MediaTypes.TEXT_X_SPEECH);


    final Function<Integer, Route> nummerHandler = integer -> completeOK(integer, numberAsNameMarshaller);

    TestRoute route =
      testRoute(
        get(() ->
          path("nummer", () ->
            parameter(StringUnmarshallers.INTEGER, "n", nummerHandler)
          )
        )
      );

    route.run(HttpRequest.GET("/nummer?n=1"))
      .assertStatusCode(StatusCodes.OK)
      .assertMediaType(MediaTypes.TEXT_X_SPEECH)
      .assertEntity("eins");

    route.run(HttpRequest.GET("/nummer?n=6"))
      .assertStatusCode(StatusCodes.OK)
      .assertMediaType(MediaTypes.TEXT_X_SPEECH)
      .assertEntity("wat?");

    route.run(HttpRequest.GET("/nummer?n=5"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntityBytes(ByteString.fromString("fünf", "utf8"));

    route.run(
      HttpRequest.GET("/nummer?n=5")
        .addHeader(AcceptCharset.create(HttpCharsets.ISO_8859_1.toRange())))
      .assertStatusCode(StatusCodes.OK)
      .assertEntityBytes(ByteString.fromString("fünf", "ISO-8859-1"));
  }

  @Test
  public void testCustomToByteStringMarshaller() {
    final Marshaller<Integer, RequestEntity> numberAsJsonListMarshaller =
      Marshaller.wrapEntity((Integer param) -> {
        switch (param) {
          case 1:
            return ByteString.fromString("[1]");
          case 5:
            return ByteString.fromString("[1,2,3,4,5]");
          default:
            return ByteString.fromString("[]");
        }
      }, Marshaller.byteStringToEntity(), MediaTypes.APPLICATION_JSON);

    final Function<Integer, Route> nummerHandler = integer -> completeOK(integer, numberAsJsonListMarshaller);

    TestRoute route =
      testRoute(
        get(() ->
          path("nummer", () ->
            parameter(StringUnmarshallers.INTEGER, "n", nummerHandler)
          )
        )
      );

    route.run(HttpRequest.GET("/nummer?n=1"))
      .assertStatusCode(StatusCodes.OK)
      .assertMediaType(MediaTypes.APPLICATION_JSON)
      .assertEntity("[1]");

    route.run(HttpRequest.GET("/nummer?n=5"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("[1,2,3,4,5]");

    route.run(
      HttpRequest.GET("/nummer?n=5").addHeader(Accept.create(MediaTypes.TEXT_PLAIN.toRange())))
      .assertStatusCode(StatusCodes.NOT_ACCEPTABLE);
  }

  @Test
  public void testCustomToEntityMarshaller() {
    final Marshaller<Integer, RequestEntity> numberAsJsonListMarshaller =
      Marshaller.withFixedContentType(MediaTypes.APPLICATION_JSON.toContentType(), (Integer param) -> {
        switch (param) {
          case 1:
            return HttpEntities.create(MediaTypes.APPLICATION_JSON.toContentType(), "[1]");
          case 5:
            return HttpEntities.create(MediaTypes.APPLICATION_JSON.toContentType(), "[1,2,3,4,5]");
          default:
            return HttpEntities.create(MediaTypes.APPLICATION_JSON.toContentType(), "[]");
        }
      });

    final Function<Integer, Route> nummerHandler = integer -> completeOK(integer, numberAsJsonListMarshaller);

    TestRoute route =
      testRoute(
        get(() ->
          path("nummer", () ->
            parameter(StringUnmarshallers.INTEGER, "n", nummerHandler)
          )
        ).seal(system(), materializer()) // needed to get the content negotiation, maybe
      );

    route.run(HttpRequest.GET("/nummer?n=1"))
      .assertStatusCode(StatusCodes.OK)
      .assertMediaType(MediaTypes.APPLICATION_JSON)
      .assertEntity("[1]");

    route.run(HttpRequest.GET("/nummer?n=5"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("[1,2,3,4,5]");

    route.run(
      HttpRequest.GET("/nummer?n=5").addHeader(Accept.create(MediaTypes.TEXT_PLAIN.toRange())))
        .assertStatusCode(StatusCodes.NOT_ACCEPTABLE);
  }

  @Test
  public void testCustomToResponseMarshaller() {
    final Marshaller<Integer, HttpResponse> numberAsJsonListMarshaller =
      Marshaller.withFixedContentType(MediaTypes.APPLICATION_JSON.toContentType(), (Integer param) -> {
        switch (param) {
          case 1:
            return HttpResponse.create().withEntity(MediaTypes.APPLICATION_JSON.toContentType(), "[1]");
          case 5:
            return HttpResponse.create().withEntity(MediaTypes.APPLICATION_JSON.toContentType(), "[1,2,3,4,5]");
          default:
            return HttpResponse.create().withStatus(StatusCodes.NOT_FOUND);
        }
      });

    final Function<Integer, Route> nummerHandler = integer -> complete(integer, numberAsJsonListMarshaller);

    TestRoute route =
      testRoute(
        get(() ->
          path("nummer", () ->
            parameter(StringUnmarshallers.INTEGER, "n", nummerHandler)
          )
        )
      );

    route.run(HttpRequest.GET("/nummer?n=1"))
      .assertStatusCode(StatusCodes.OK)
      .assertMediaType(MediaTypes.APPLICATION_JSON)
      .assertEntity("[1]");

    route.run(HttpRequest.GET("/nummer?n=5"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("[1,2,3,4,5]");

    route.run(HttpRequest.GET("/nummer?n=6"))
      .assertStatusCode(StatusCodes.NOT_FOUND);

    route.run(HttpRequest.GET("/nummer?n=5").addHeader(Accept.create(MediaTypes.TEXT_PLAIN.toRange())))
      .assertStatusCode(StatusCodes.NOT_ACCEPTABLE);
  }
}
