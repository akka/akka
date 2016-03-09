/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.examples.simple;

import static akka.http.javadsl.server.PathMatchers.INTEGER_SEGMENT;
import static akka.http.javadsl.server.PathMatcher.segment;
import static akka.http.javadsl.server.StringUnmarshallers.INTEGER;
import static akka.http.javadsl.server.Unmarshaller.entityToString;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;

import akka.actor.ActorSystem;
import akka.http.javadsl.server.HttpApp;
import akka.http.javadsl.server.Route;

public class SimpleServerApp extends HttpApp {

    public Route multiply(int x, int y) {
        int result = x * y;
        return complete(String.format("%d * %d = %d", x, y, result));
    }
    
    public CompletionStage<Route> multiplyAsync(Executor ctx, int x, int y) {
        return CompletableFuture.supplyAsync(() -> multiply(x, y), ctx);
    }

    @Override
    public Route createRoute() {
        Route addHandler = param(INTEGER, "x", x ->
            param(INTEGER, "y", y -> {
                int result = x + y;
                return complete(String.format("%d + %d = %d", x, y, result));
            })
        );
        
        BiFunction<Integer, Integer, Route> subtractHandler = (x, y) -> {
            int result = x - y;
            return complete(String.format("%d - %d = %d", x, y, result));            
        };
        
        return
            route(
                // matches the empty path
                pathSingleSlash(() ->
                    getFromResource("web/calculator.html")
                ),
                // matches paths like this: /add?x=42&y=23
                path("add", () -> addHandler),
                path("subtract", () ->
                    param(INTEGER, "x", x ->
                        param(INTEGER, "y", y ->
                            subtractHandler.apply(x, y)
                        )
                    )
                ),
                // matches paths like this: /multiply/{x}/{y}
                path(segment("multiply").slash(INTEGER_SEGMENT).slash(INTEGER_SEGMENT), 
                    this::multiply
                ),
                path(segment("multiplyAsync").slash(INTEGER_SEGMENT).slash(INTEGER_SEGMENT), (x, y) ->
                    extractExecutionContext(ctx ->
                        onSuccess(() -> multiplyAsync(ctx, x, y), Function.identity())
                    )
                ),
                post(() ->
                    path("hello", () ->
                        entity(entityToString(), body ->
                            complete("Hello " + body + "!")
                        )
                    )
                )
            );
    }

    public static void main(String[] args) throws IOException {
        ActorSystem system = ActorSystem.create();
        new SimpleServerApp().bindRoute("localhost", 8080, system);
        System.out.println("Type RETURN to exit");
        System.in.read();
        system.terminate();
    }
}