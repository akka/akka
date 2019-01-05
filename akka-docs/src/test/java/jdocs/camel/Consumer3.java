/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.camel;
// #Consumer3
import akka.actor.Status;
import akka.camel.Ack;
import akka.camel.CamelMessage;
import akka.camel.javaapi.UntypedConsumerActor;

public class Consumer3 extends UntypedConsumerActor {

  @Override
  public boolean autoAck() {
    return false;
  }

  public String getEndpointUri() {
    return "jms:queue:test";
  }

  public void onReceive(Object message) {
    if (message instanceof CamelMessage) {
      getSender().tell(Ack.getInstance(), getSelf());
      // on success
      // ..
      Exception someException = new Exception("e1");
      // on failure
      getSender().tell(new Status.Failure(someException), getSelf());
    } else unhandled(message);
  }
}
// #Consumer3
