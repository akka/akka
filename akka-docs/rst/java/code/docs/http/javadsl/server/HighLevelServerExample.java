/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server;

//#high-level-server-example
import java.io.IOException;

import akka.actor.ActorSystem;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntities;
import akka.http.javadsl.server.HttpApp;
import akka.http.javadsl.server.Route;

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

    @Override
    public Route createRoute() {
        // This handler generates responses to `/hello?name=XXX` requests
        Route helloRoute =
            paramOptional("name", optName -> {
                String name = optName.orElse("Mister X");
                return complete("Hello " +name + "!");
            });

        return
            // here the complete behavior for this server is defined
                
            // only handle GET requests
            get(() -> route(
                // matches the empty path
                pathSingleSlash(() ->
                    // return a constant string with a certain content type
                    complete(HttpEntities.create(ContentTypes.TEXT_HTML_UTF8, "<html><body>Hello world!</body></html>"))
                ),
                path("ping", () ->
                    // return a simple `text/plain` response
                    complete("PONG!")
                ),
                path("hello", () ->
                    // uses the route defined above
                    helloRoute
                )
            ));
    }
}
//#high-level-server-example