//#full-example
package jdocs.quickstart;

import java.io.IOException;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.ActorSystem;

public class HelloWorldMain {
  public static void main(String[] args) throws IOException {
    //#create-send
    ActorSystem system = ActorSystem.create("hello-world-actor-system");
    try {
      // Create hello world actor
      ActorRef helloWorldActor = system.actorOf(Props.create(HelloWorldActor.class), "HelloWorldActor");
      // Send message to actor
      helloWorldActor.tell("World", ActorRef.noSender());

      System.out.println("Press ENTER to exit the system");
      System.in.read();
    } finally {
      system.terminate();
    }
    //#create-send
  }
}

//#full-example
