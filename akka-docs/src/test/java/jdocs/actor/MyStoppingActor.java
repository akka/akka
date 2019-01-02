/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.actor;

//#my-stopping-actor
import akka.actor.ActorRef;
import akka.actor.AbstractActor;

public class MyStoppingActor extends AbstractActor {

  ActorRef child = null;

  // ... creation of child ...

  @Override
  public Receive createReceive() {
    return receiveBuilder()
      .matchEquals("interrupt-child", m ->
        getContext().stop(child)
      )
      .matchEquals("done", m ->
        getContext().stop(getSelf())
      )
      .build();
  }
}
//#my-stopping-actor

