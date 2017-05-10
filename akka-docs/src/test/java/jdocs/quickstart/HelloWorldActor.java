package jdocs.quickstart;

import akka.actor.AbstractActor;
import akka.actor.AbstractActor.Receive;

//#actor-impl
public class HelloWorldActor extends AbstractActor {
  public Receive createReceive() {
    return receiveBuilder()
      .match(String.class, msg -> {
        System.out.println("Hello " + msg);
      })
      .build();
  }
}
//#actor-impl