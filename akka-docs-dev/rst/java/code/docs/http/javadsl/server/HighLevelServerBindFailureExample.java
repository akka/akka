/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.javadsl.server;

//#binding-failure-high-level-example
import akka.actor.ActorSystem;
import akka.dispatch.OnFailure;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.server.*;
import akka.http.javadsl.server.values.Parameters;
import akka.http.scaladsl.Http;
import scala.concurrent.Future;

import java.io.IOException;

@SuppressWarnings("unchecked")
public class HighLevelServerBindFailureExample {
    public static void main(String[] args) throws IOException {
        // boot up server using the route as defined below
        final ActorSystem system = ActorSystem.create();

        // HttpApp.bindRoute expects a route being provided by HttpApp.createRoute
        Future<Http.ServerBinding> bindingFuture =
                new HighLevelServerExample().bindRoute("localhost", 8080, system);

        bindingFuture.onFailure(new OnFailure() {
            @Override
            public void onFailure(Throwable failure) throws Throwable {
                System.err.println("Something very bad happened! " + failure.getMessage());
                system.shutdown();
            }
        });

        system.shutdown();
    }
}
//#binding-failure-high-level-example
