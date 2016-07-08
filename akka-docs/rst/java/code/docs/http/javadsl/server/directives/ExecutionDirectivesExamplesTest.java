/*
 * Copyright (C) 2016-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server.directives;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.ExceptionHandler;
import akka.http.javadsl.server.PathMatchers;
import akka.http.javadsl.server.RejectionHandler;
import akka.http.javadsl.server.Rejections;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.ValidationRejection;
import akka.http.javadsl.testkit.JUnitRouteTest;
import org.junit.Test;

import static akka.http.javadsl.server.PathMatchers.integerSegment;

public class ExecutionDirectivesExamplesTest extends JUnitRouteTest {

  @Test
  public void testHandleExceptions() {
    //#handleExceptions
    final ExceptionHandler divByZeroHandler = ExceptionHandler.newBuilder()
      .match(ArithmeticException.class, x ->
        complete(StatusCodes.BAD_REQUEST, "You've got your arithmetic wrong, fool!"))
      .build();

    final Route route =
      path(PathMatchers.segment("divide").slash(integerSegment()).slash(integerSegment()), (a, b) ->
        handleExceptions(divByZeroHandler, () -> complete("The result is " + (a / b)))
      );

    // tests:
    testRoute(route).run(HttpRequest.GET("/divide/10/5"))
      .assertEntity("The result is 2");
    testRoute(route).run(HttpRequest.GET("/divide/10/0"))
      .assertStatusCode(StatusCodes.BAD_REQUEST)
      .assertEntity("You've got your arithmetic wrong, fool!");
    //#handleExceptions
  }

  @Test
  public void testHandleRejections() {
    //#handleRejections
    final RejectionHandler totallyMissingHandler = RejectionHandler.newBuilder()
      .handleNotFound(complete(StatusCodes.NOT_FOUND, "Oh man, what you are looking for is long gone."))
      .handle(ValidationRejection.class, r -> complete(StatusCodes.INTERNAL_SERVER_ERROR, r.message()))
      .build();

    final Route route = pathPrefix("handled", () ->
      handleRejections(totallyMissingHandler, () ->
        route(
          path("existing", () -> complete("This path exists")),
          path("boom", () -> reject(Rejections.validationRejection("This didn't work.")))
        )
      )
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/handled/existing"))
      .assertEntity("This path exists");
    // applies default handler
    testRoute(route).run(HttpRequest.GET("/missing"))
      .assertStatusCode(StatusCodes.NOT_FOUND)
      .assertEntity("The requested resource could not be found.");
    testRoute(route).run(HttpRequest.GET("/handled/missing"))
      .assertStatusCode(StatusCodes.NOT_FOUND)
      .assertEntity("Oh man, what you are looking for is long gone.");
    testRoute(route).run(HttpRequest.GET("/handled/boom"))
      .assertStatusCode(StatusCodes.INTERNAL_SERVER_ERROR)
      .assertEntity("This didn't work.");
    //#handleRejections
  }
}
