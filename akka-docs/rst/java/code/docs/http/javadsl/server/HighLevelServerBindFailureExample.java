/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server;

//#binding-failure-high-level-example
import akka.actor.ActorSystem;
import akka.http.scaladsl.Http;

import java.io.IOException;
import java.util.concurrent.CompletionStage;

public class HighLevelServerBindFailureExample {
    public static void main(String[] args) throws IOException {
        // boot up server using the route as defined below
        final ActorSystem system = ActorSystem.create();

        // HttpApp.bindRoute expects a route being provided by HttpApp.createRoute
        CompletionStage<Http.ServerBinding> bindingFuture =
                new HighLevelServerExample().bindRoute("localhost", 8080, system);

        bindingFuture.exceptionally(failure -> {
                System.err.println("Something very bad happened! " + failure.getMessage());
                system.terminate();
                return null;
        });

        system.terminate();
    }
}
//#binding-failure-high-level-example
