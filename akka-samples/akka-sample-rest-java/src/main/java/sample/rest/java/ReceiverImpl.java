/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package sample.rest.java;

import akka.actor.TypedActor;

public class ReceiverImpl extends TypedActor implements Receiver {
  public SimpleService get() {
    return (SimpleService) getContext().getSender();
  }
}
