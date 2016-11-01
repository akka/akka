/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server;

import akka.actor.ActorSystem;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;

public class BlockingInHttpExamples extends JUnitRouteTest {

    @Test
    public void compileOnlySpec() throws Exception {
        // just making sure for it to be really compiled / run even if empty
    }

    void blockingHttpDefaultDispatcher() {
        //#blocking-example-in-default-dispatcher
        // BAD (due to blocking in Future, on default dispatcher)
        final Route routes = post( () ->
                completeWithFuture(CompletableFuture.supplyAsync(() -> { // uses defaultDispatcher
                    try {
                        Thread.sleep(5000L); // will block on default dispatcher,
                    } catch (InterruptedException e) {
                    }
                    return HttpResponse.create() // Starving the routing infrastructure
                            .withEntity(Long.toString(System.currentTimeMillis()));
                }))
        );
        //#blocking-example-in-default-dispatcher
    }

    void blockingHttpDedicatedDispatcher() {
        final ActorSystem system = ActorSystem.create();
        //#blocking-example-in-dedicated-dispatcher
        // GOOD (the blocking is now isolated onto a dedicated dispatcher):
        final Route routes = post( () ->
                completeWithFuture(CompletableFuture.supplyAsync(() -> {
                            try {
                                Thread.sleep(5000L);
                            } catch (InterruptedException e) {
                            }
                            return HttpResponse.create()
                                    .withEntity(Long.toString(System.currentTimeMillis()));
                        }, system.dispatchers().lookup("my-blocking-dispatcher") // uses the good "blocking dispatcher" that we
                        // configured, instead of the default dispatcher- the blocking is isolated.
                )));
        //#blocking-example-in-dedicated-dispatcher
    }

}
