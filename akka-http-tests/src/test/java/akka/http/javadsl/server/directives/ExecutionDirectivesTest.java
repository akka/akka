/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntity;
import org.junit.Test;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.ExceptionHandler;
import akka.http.javadsl.server.RejectionHandler;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.unmarshalling.StringUnmarshallers;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import akka.http.scaladsl.server.MethodRejection;
import akka.http.scaladsl.server.Rejection;

public class ExecutionDirectivesTest extends JUnitRouteTest {
  @Test
  public void testCatchExceptionThrownFromHandler() {
    Route divide =
      path("divide", () ->
        parameter(StringUnmarshallers.INTEGER, "a", a ->
          parameter(StringUnmarshallers.INTEGER, "b", b ->
            complete("The result is: " + (a / b)))));


    ExceptionHandler handleDivByZero = ExceptionHandler.newBuilder()
      .match(ArithmeticException.class, t -> complete(StatusCodes.BAD_REQUEST, "Congratulations you provoked a division by zero!"))
      .build();

    TestRoute route =
      testRoute(
        handleExceptions(handleDivByZero, () -> divide)
      );

    route.run(HttpRequest.GET("/divide?a=10&b=5"))
      .assertEntity("The result is: 2");

    route.run(HttpRequest.GET("/divide?a=10&b=0"))
      .assertStatusCode(StatusCodes.BAD_REQUEST)
      .assertEntity("Congratulations you provoked a division by zero!");
  }

  @Test
  public void testHandleMethodRejection() {
    RejectionHandler rejectionHandler = RejectionHandler.newBuilder()
      .handle(MethodRejection.class, r -> complete(StatusCodes.BAD_REQUEST, "Whoopsie! Unsupported method. Supported would have been " + r.supported().value()))
      .build();

    TestRoute route =
      testRoute(
        handleRejections(rejectionHandler, () ->
          get(() -> complete("Successful!"))
        )
      );

    route.run(HttpRequest.GET("/"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("Successful!");

    route.run(HttpRequest.POST("/"))
      .assertStatusCode(StatusCodes.BAD_REQUEST)
      .assertEntity("Whoopsie! Unsupported method. Supported would have been GET");
  }

  public static final class TooManyRequestsRejection implements Rejection {
    final public String message;

    TooManyRequestsRejection(String message) {
      this.message = message;
    }
  }

  private final Route testRoute = extractUri(uri -> {
    if (uri.path().startsWith("/test"))
      return complete("Successful!");
    else
      return reject(new TooManyRequestsRejection("Too many requests for busy path!"));
  });

  @Test
  public void testHandleCustomRejection() {
    RejectionHandler rejectionHandler = RejectionHandler.newBuilder()
      .handle(TooManyRequestsRejection.class, rej -> complete(StatusCodes.TOO_MANY_REQUESTS, rej.message))
      .build();

    TestRoute route = testRoute(handleRejections(rejectionHandler, () -> testRoute));

    route.run(HttpRequest.GET("/test"))
      .assertStatusCode(StatusCodes.OK);

    route.run(HttpRequest.GET("/other"))
      .assertStatusCode(StatusCodes.TOO_MANY_REQUESTS)
      .assertEntity("Too many requests for busy path!");
  }

  @Test
  public void testHandleCustomRejectionResponse() {
    final RejectionHandler rejectionHandler = RejectionHandler.defaultHandler()
      .mapRejectionResponse(response -> {
        if (response.entity() instanceof HttpEntity.Strict) {
          String message = ((HttpEntity.Strict) response.entity()).getData().utf8String().replaceAll("\"", "\\\"");
          return response.withEntity(ContentTypes.APPLICATION_JSON, "{\"rejection\": \"" + message + "\"}");
        } else {
          return response;
        }
      });

    Route route = handleRejections(rejectionHandler, () ->
      path("hello", () ->
        complete("Hello there")
      ));

    testRoute(route)
      .run(HttpRequest.GET("/nope"))
      .assertStatusCode(StatusCodes.NOT_FOUND)
      .assertContentType(ContentTypes.APPLICATION_JSON)
      .assertEntity("{\"rejection\": \"The requested resource could not be found.\"}");

    Route anotherOne = handleRejections(rejectionHandler, () ->
      validate(() -> false, "Whoops, bad request!", () ->
        complete("Hello there")
      ));

    testRoute(anotherOne)
      .run(HttpRequest.GET("/hello"))
      .assertStatusCode(StatusCodes.BAD_REQUEST)
      .assertContentType(ContentTypes.APPLICATION_JSON)
      .assertEntity("{\"rejection\": \"Whoops, bad request!\"}");
  }
}
