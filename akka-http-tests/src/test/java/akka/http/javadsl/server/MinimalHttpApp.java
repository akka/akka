/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server;

import akka.Done;
import akka.actor.ActorSystem;
import akka.http.javadsl.ServerBinding;
import scala.runtime.BoxedUnit;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;

public class MinimalHttpApp extends HttpApp {

  CompletableFuture<Done> shutdownTrigger = new CompletableFuture<>();
  CompletableFuture<Done> bindingPromise = new CompletableFuture<>();

  @Override
  protected Route route() {
    return route(path("foo", () ->
        complete("bar")
      ),
      path("shutdown", () -> {
        if (shutdownTrigger.complete(Done.getInstance())) {
          return complete("Shutdown request accepted");
        } else {
          return complete("Shutdown is already in progress");
        }
      }));
  }

  @Override
  protected void postHttpBinding(ServerBinding binding) {
    super.postHttpBinding(binding);
    bindingPromise.complete(Done.getInstance());
  }

  @Override
  protected CompletionStage<Done> waitForShutdownSignal(ActorSystem system) {
    return shutdownTrigger;
  }
}
