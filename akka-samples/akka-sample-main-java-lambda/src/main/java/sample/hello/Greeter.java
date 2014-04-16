package sample.hello;

import akka.actor.AbstractActor;
import akka.japi.pf.ReceiveBuilder;

public class Greeter extends AbstractActor {

  public static enum Msg {
    GREET, DONE;
  }

  public Greeter() {
    receive(ReceiveBuilder.
      matchEquals(Msg.GREET, m -> {
        System.out.println("Hello World!");
        sender().tell(Msg.DONE, self());
      }).build());
  }
}
