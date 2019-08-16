/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.actor.typed;

import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;

public class BlockingActor extends AbstractBehavior<Integer> {
  public static Behavior<Integer> create() {
    return Behaviors.setup(BlockingActor::new);
  }

  private BlockingActor(ActorContext<Integer> context) {}

  @Override
  public Receive<Integer> createReceive() {
    return newReceiveBuilder()
        .onMessage(
            Integer.class,
            i -> {
              Thread.sleep(5000); // block for 5 seconds, representing blocking I/O, etc
              System.out.println("Blocking operation finished: " + i);
              return Behaviors.same();
            })
        .build();
  }
}
