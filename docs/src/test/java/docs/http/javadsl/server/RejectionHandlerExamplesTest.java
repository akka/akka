/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server;

import akka.http.javadsl.coding.Coder;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.AuthorizationFailedRejection;
import akka.http.javadsl.server.MethodRejection;
import akka.http.javadsl.server.MissingCookieRejection;
import akka.http.javadsl.server.RejectionHandler;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.ValidationRejection;
import akka.http.javadsl.testkit.JUnitRouteTest;
import org.junit.Test;

import java.util.stream.Collectors;

public class RejectionHandlerExamplesTest extends JUnitRouteTest {

  @Test
  public void compileOnlySpec() throws Exception {
    // just making sure for it to be really compiled / run even if empty
  }

  void example1() {
    //#example1
    final Route route = path("order", () ->
      route(
        get(() ->
          complete("Received GET")
        ),
        post(() ->
          decodeRequestWith(Coder.Gzip, () ->
            complete("Received compressed POST")
          )
        )
      ));
    //#example1
  }

  void customRejectionHandler() {
    //#custom-handler-example-java
    final RejectionHandler rejectionHandler = RejectionHandler.newBuilder()
      .handle(MissingCookieRejection.class, rej ->
        complete(StatusCodes.BAD_REQUEST, "No cookies, no service!!!")
      )
      .handle(AuthorizationFailedRejection.class, rej ->
        complete(StatusCodes.FORBIDDEN, "You're out of your depth!")
      )
      .handle(ValidationRejection.class, rej ->
        complete(StatusCodes.INTERNAL_SERVER_ERROR, "That wasn't valid! " + rej.message())
      )
      .handleAll(MethodRejection.class, rejections -> {
        String supported = rejections.stream()
          .map(rej -> rej.supported().name())
          .collect(Collectors.joining(" or "));
        return complete(StatusCodes.METHOD_NOT_ALLOWED, "Can't do that! Supported: " + supported + "!");
      })
      .handleNotFound(complete(StatusCodes.NOT_FOUND, "Not here!"))
      .build();

    // Route that will be bound to the Http
    final Route wrapped = handleRejections(rejectionHandler,
      this::getRoute); // Some route structure for this Server
    //#custom-handler-example-java
  }

  Route getRoute() {
    return null;
  }

  @Test
  public void customRejectionResponse() {
    //#example-json
    final RejectionHandler rejectionHandler = RejectionHandler.defaultHandler()
      .mapRejectionResponse(response -> {
        if (response.entity() instanceof HttpEntity.Strict) {
          // since all Akka default rejection responses are Strict this will handle all rejections
          String message = ((HttpEntity.Strict) response.entity()).getData().utf8String()
            .replaceAll("\"", "\\\"");
          // we create a new copy the response in order to keep all headers and status code,
          // replacing the original entity with a custom message as hand rolled JSON you could the
          // entity using your favourite marshalling library (e.g. spray json or anything else)
          return response.withEntity(ContentTypes.APPLICATION_JSON,
            "{\"rejection\": \"" + message + "\"}");
        } else {
          // pass through all other types of responses
          return response;
        }
      });

    Route route = handleRejections(rejectionHandler, () ->
      path("hello", () ->
        complete("Hello there")
      ));

    // tests:
    testRoute(route)
      .run(HttpRequest.GET("/nope"))
      .assertStatusCode(StatusCodes.NOT_FOUND)
      .assertContentType(ContentTypes.APPLICATION_JSON)
      .assertEntity("{\"rejection\": \"The requested resource could not be found.\"}");

    Route anotherOne = handleRejections(rejectionHandler, () ->
      validate(() -> false, "Whoops, bad request!", () ->
        complete("Hello there")
    ));

    // tests:
    testRoute(anotherOne)
      .run(HttpRequest.GET("/hello"))
      .assertStatusCode(StatusCodes.BAD_REQUEST)
      .assertContentType(ContentTypes.APPLICATION_JSON)
      .assertEntity("{\"rejection\": \"Whoops, bad request!\"}");
    //#example-json
  }

}