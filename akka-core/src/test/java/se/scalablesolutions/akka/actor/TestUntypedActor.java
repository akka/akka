package se.scalablesolutions.akka.actor;

import se.scalablesolutions.akka.actor.*;

public class TestUntypedActor extends UntypedActor {
  public void onReceive(Object message, ActorContext context) {
    System.out.println("TestUntypedActor got " + message);
  }
}