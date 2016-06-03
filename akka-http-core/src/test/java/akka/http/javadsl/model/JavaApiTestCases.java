/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model;

import akka.http.javadsl.model.headers.*;
import akka.japi.Pair;
import akka.stream.javadsl.Source;
import akka.util.ByteString;

public class JavaApiTestCases {
  /**
   * Builds a request for use on the client side
   */
  public static HttpRequest buildRequest() {
    return
      HttpRequest.create()
                 .withMethod(HttpMethods.POST)
                 .withUri("/send");
  }

  /**
   * A simple handler for an Http server
   */
  public static HttpResponse handleRequest(HttpRequest request) {
    if (request.method() == HttpMethods.GET) {
      Uri uri = request.getUri();
      if (uri.path().equals("/hello")) {
        String name = uri.query().get("name").orElse("Mister X");

        return
          HttpResponse.create()
                      .withEntity("Hello " + name + "!");
      } else {
        return
          HttpResponse.create()
                      .withStatus(404)
                      .withEntity("Not found");
      }
    } else {
      return
        HttpResponse.create()
                    .withStatus(StatusCodes.METHOD_NOT_ALLOWED)
                    .withEntity("Unsupported method");
    }
  }

  /**
   * Adds authentication to an existing request
   */
  public static HttpRequest addAuthentication(HttpRequest request) {
    // unused here but just to show the shortcut
    request.addHeader(Authorization.basic("username", "password"));

    return request
      .addHeader(Authorization.create(HttpCredentials.createBasicHttpCredentials("username", "password")));

  }

  /**
   * Removes cookies from an existing request
   */
  public static HttpRequest removeCookies(HttpRequest request) {
    return request.removeHeader("Cookie");
  }

  /**
   * Build a uri to send a form
   */
  public static Uri createUriForOrder(String orderId, String price, String amount) {
    return Uri.create("/order").query(
      Query.create(
        Pair.create("orderId", orderId),
        Pair.create("price", price),
        Pair.create("amount", amount)));
  }

  public static Query addSessionId(Query query) {
    return query.withParam("session", "abcdefghijkl");
  }

  public static Object accessScalaDefinedJavadslContentTypeAndMediaType(ContentType type) {
    Object anything = null;

    akka.http.javadsl.model.MediaType mediaType = type.mediaType();

    // just for the sake of explicitly touching the interfaces
    if (mediaType.binary()) anything = (akka.http.javadsl.model.MediaType.Binary) mediaType;
    anything = (akka.http.javadsl.model.MediaType.Multipart) mediaType;
    anything = (akka.http.javadsl.model.MediaType.WithOpenCharset) mediaType;
    anything = (akka.http.javadsl.model.MediaType.WithFixedCharset) mediaType;

    if (type.binary()) anything = (akka.http.javadsl.model.ContentType.Binary) type;
    anything = (akka.http.javadsl.model.ContentType.NonBinary) type;
    anything = (akka.http.javadsl.model.ContentType.WithCharset) type;
    anything = (akka.http.javadsl.model.ContentType.WithFixedCharset) type;

    return anything;
  }
  
  public static void HttpEntity_should_not_care_about_materialized_value_of_its_source() {
      Source<ByteString, Integer> src = Source.single(ByteString.fromString("hello, world")).mapMaterializedValue(m -> 42);
      HttpEntity entity = HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8, src); // this needs to accept Source<ByteString, ?>
  }
}
