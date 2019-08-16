/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.actor.typed;

import akka.actor.typed.*;
import akka.actor.typed.javadsl.*;

// #print-actor
class PrintActor extends AbstractBehavior<Integer> {

  @Override
  public Receive<Integer> createReceive() {
    return newReceiveBuilder()
        .onMessage(
            Integer.class,
            i -> {
              System.out.println("PrintActor: " + i);
              return Behaviors.same();
            })
        .build();
  }
}
// #print-actor
