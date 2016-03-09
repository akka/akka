/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives;

import org.junit.Test;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.ExceptionHandler;
import akka.http.javadsl.server.RejectionHandler;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.StringUnmarshallers;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import akka.http.scaladsl.server.MethodRejection;
import akka.http.scaladsl.server.Rejection;

public class ExecutionDirectivesTest extends JUnitRouteTest {
    @Test
    public void testCatchExceptionThrownFromHandler() {
        Route divide = 
            path("divide", () ->
                param(StringUnmarshallers.INTEGER, "a", a -> 
                    param(StringUnmarshallers.INTEGER, "b", b ->
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
            .assertStatusCode(400)
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
            .assertStatusCode(200)
            .assertEntity("Successful!");

        route.run(HttpRequest.POST("/"))
            .assertStatusCode(400)
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
                .assertStatusCode(200);

        route.run(HttpRequest.GET("/other"))
                .assertStatusCode(429)
                .assertEntity("Too many requests for busy path!");
    }
}
