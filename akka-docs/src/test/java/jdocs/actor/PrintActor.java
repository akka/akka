/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
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
