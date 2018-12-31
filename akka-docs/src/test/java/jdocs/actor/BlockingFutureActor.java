/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.actor;

import akka.actor.AbstractActor;
import akka.dispatch.Futures;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;

// #blocking-in-future
class BlockingFutureActor extends AbstractActor {
  ExecutionContext ec = getContext().dispatcher();

  @Override
  public Receive createReceive() {
    return receiveBuilder()
      .match(Integer.class, i -> {
        System.out.println("Calling blocking Future: " + i);
        Future<Integer> f = Futures.future(() -> {
          Thread.sleep(5000);
          System.out.println("Blocking future finished: " + i);
          return i;
        }, ec);
      })
      .build();
  }
}
// #blocking-in-future