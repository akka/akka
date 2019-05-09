/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.actor;

import akka.actor.AbstractActor;

// #blocking-in-actor
class BlockingActor extends AbstractActor {

  @Override
  public Receive createReceive() {
    return receiveBuilder()
        .match(
            Integer.class,
            i -> {
              Thread.sleep(5000); // block for 5 seconds, representing blocking I/O, etc
              System.out.println("Blocking operation finished: " + i);
            })
        .build();
  }
}
// #blocking-in-actor
