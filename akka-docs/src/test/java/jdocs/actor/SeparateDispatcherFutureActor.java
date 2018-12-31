/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.actor;

import akka.actor.AbstractActor;
import akka.dispatch.Futures;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;

// #separate-dispatcher
class SeparateDispatcherFutureActor extends AbstractActor {
  ExecutionContext ec = getContext().getSystem().dispatchers().lookup("my-blocking-dispatcher");

  @Override
  public Receive createReceive() {
    return receiveBuilder()
      .match(Integer.class, i -> {
        System.out.println("Calling blocking Future on separate dispatcher: " + i);
        Future<Integer> f = Futures.future(() -> {
          Thread.sleep(5000);
          System.out.println("Blocking future finished: " + i);
          return i;
        }, ec);
      })
      .build();
  }
}
// #separate-dispatcher