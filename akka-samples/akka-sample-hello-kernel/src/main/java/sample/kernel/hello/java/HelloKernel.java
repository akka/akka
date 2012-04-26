/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.kernel.hello.java;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.kernel.Bootable;

public class HelloKernel implements Bootable {
  final ActorSystem system = ActorSystem.create("hellokernel");

  static class HelloActor extends UntypedActor {
    final ActorRef worldActor = 
      getContext().actorOf(new Props(WorldActor.class));

  public void onReceive(Object message) {
    if (message == "start")
      worldActor.tell("Hello");
    else if (message instanceof String)
      System.out.println("Received message '%s'".format((String)message));
    else unhandled(message);
  }
}

static class WorldActor extends UntypedActor {
  public void onReceive(Object message) {
    if (message instanceof String)
      getSender().tell(((String)message).toUpperCase() + " world!");
    else unhandled(message);
  }
}

  public void startup() {
    system.actorOf(new Props(HelloActor.class)).tell("start");
  }

  public void shutdown() {
    system.shutdown();
  }
}
