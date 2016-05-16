/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server;

import static akka.http.javadsl.server.StringUnmarshallers.INTEGER;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.junit.Test;

import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.MediaTypes;
import akka.http.javadsl.testkit.JUnitRouteTest;

public class CompleteTest extends JUnitRouteTest {
    @Test
    public void completeWithString() {
        Route route = complete("Everything OK!");

        HttpRequest request = HttpRequest.create();

        runRoute(route, request)
            .assertStatusCode(200)
            .assertMediaType(MediaTypes.TEXT_PLAIN)
            .assertEntity("Everything OK!");
    }

    @Test
    public void completeAsJacksonJson() {
        
        @SuppressWarnings("unused") // The getters are used reflectively by Jackson
        class Person {
            public String getFirstName() { return "Peter"; }
            public String getLastName() { return "Parker"; }
            public int getAge() { return 138; }
        }
        Route route = completeOK(new Person(), Jackson.marshaller());

        HttpRequest request = HttpRequest.create();

        runRoute(route, request)
            .assertStatusCode(200)
            .assertMediaType("application/json")
            .assertEntity("{\"age\":138,\"firstName\":\"Peter\",\"lastName\":\"Parker\"}");
    }
    
    private CompletionStage<String> doSlowCalculation(int x, int y) {
        return CompletableFuture.supplyAsync(() -> {
            int result = x + y;
            return String.format("%d + %d = %d",x, y, result);
        });
    }
    
    @Test
    public void completeWithFuture() {
        Route route = 
            parameter(INTEGER, "x", x ->
                parameter(INTEGER, "y", y ->
                    onSuccess(() -> doSlowCalculation(x, y), Directives::complete)
                )
            );

        runRoute(route, HttpRequest.GET("add?x=42&y=23"))
            .assertStatusCode(200)
            .assertEntity("42 + 23 = 65");
    }
}
