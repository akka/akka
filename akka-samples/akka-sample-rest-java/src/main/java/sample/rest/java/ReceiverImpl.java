/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package sample.rest.java;

import se.scalablesolutions.akka.actor.TypedActorContext;
import se.scalablesolutions.akka.actor.TypedActor;

public class ReceiverImpl extends TypedActor implements Receiver {
  public SimpleService receive() {
    return (SimpleService) getContext().getSender();
  }
}
