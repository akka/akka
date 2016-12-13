/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.actorlambda;

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
        getContext().stop(self())
      )
      .build();
  }
}
//#my-stopping-actor

