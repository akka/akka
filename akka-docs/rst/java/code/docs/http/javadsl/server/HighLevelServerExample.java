/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server;

//#high-level-server-example
import akka.actor.ActorSystem;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.MediaTypes;
import akka.http.javadsl.server.*;
import akka.http.javadsl.server.values.Parameters;

import java.io.IOException;

public class HighLevelServerExample extends HttpApp {
    public static void main(String[] args) throws IOException {
        // boot up server using the route as defined below
        ActorSystem system = ActorSystem.create();

        // HttpApp.bindRoute expects a route being provided by HttpApp.createRoute
        new HighLevelServerExample().bindRoute("localhost", 8080, system);
        System.out.println("Type RETURN to exit");
        System.in.read();
        system.terminate();
    }

    // A RequestVal is a type-safe representation of some aspect of the request.
    // In this case it represents the `name` URI parameter of type String.
    private RequestVal<String> name = Parameters.stringValue("name").withDefault("Mister X");

    @Override
    public Route createRoute() {
        // This handler generates responses to `/hello?name=XXX` requests
        Route helloRoute =
            handleWith1(name,
                // in Java 8 the following becomes simply
                // (ctx, name) -> ctx.complete("Hello " + name + "!")
                new Handler1<String>() {
                    @Override
                    public RouteResult apply(RequestContext ctx, String name) {
                        return ctx.complete("Hello " + name + "!");
                    }
                });

        return
            // here the complete behavior for this server is defined
            route(
                // only handle GET requests
                get(
                    // matches the empty path
                    pathSingleSlash().route(
                        // return a constant string with a certain content type
                        complete(ContentTypes.TEXT_HTML_UTF8,
                                "<html><body>Hello world!</body></html>")
                    ),
                    path("ping").route(
                        // return a simple `text/plain` response
                        complete("PONG!")
                    ),
                    path("hello").route(
                        // uses the route defined above
                        helloRoute
                    )
                )
            );
    }
}
//#high-level-server-example