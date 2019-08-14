/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.actor.typed;

import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;
import akka.dispatch.Futures;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;

// #blocking-in-future
class BlockingFutureActor extends AbstractBehavior<Integer> {
  private final ExecutionContext ec;

  public static Behavior<Integer> create() {
    return Behaviors.setup(BlockingFutureActor::new);
  }

  private BlockingFutureActor(ActorContext<Integer> context) {
    ec = context.getExecutionContext();
  }

  @Override
  public Receive createReceive() {
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
    System.out.println("Calling blocking Future: " + i);
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
// #blocking-in-future
