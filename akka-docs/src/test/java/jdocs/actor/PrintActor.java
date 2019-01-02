/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.actor;

import akka.actor.AbstractActor;

// #print-actor
class PrintActor extends AbstractActor {
  @Override
  public Receive createReceive() {
    return receiveBuilder()
      .match(Integer.class, i -> {
        System.out.println("PrintActor: " + i);
      })
      .build();
  }
}
// #print-actor
