/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.actor.typed;

import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;
import akka.dispatch.Futures;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;

// #separate-dispatcher
class SeparateDispatcherFutureActor extends AbstractBehavior<Integer> {
  final ExecutionContext ec;

  public static Behavior<Integer> create() {
    return Behaviors.setup(SeparateDispatcherFutureActor::new);
  }

  private SeparateDispatcherFutureActor(ActorContext<Integer> context) {
    ec =
        context
            .getSystem()
            .dispatchers()
            .lookup(DispatcherSelector.fromConfig("my-blocking-dispatcher"));
  }

  @Override
  public Receive<Integer> createReceive() {
    return newReceiveBuilder()
        .onMessage(
            Integer.class,
            i -> {
              triggerFutureBlockingOperation(i, ec);
              return Behaviors.same();
            })
        .build();
  }

  private static final void triggerFutureBlockingOperation(Integer i, ExecutionContext ec) {
    System.out.println("Calling blocking Future on separate dispatcher: " + i);
    Future<Integer> f =
        Futures.future(
            () -> {
              Thread.sleep(5000);
              System.out.println("Blocking future finished: " + i);
              return i;
            },
            ec);
  }
}
// #separate-dispatcher
