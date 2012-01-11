/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.jrouting;

import akka.routing.FromConfig;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.actor.ActorSystem;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.Config;

public class RouterViaConfigExample {

  public static class ExampleActor extends UntypedActor {
    public void onReceive(Object msg) {
      if (msg instanceof Message) {
        Message message = (Message) msg;
        System.out.println(String.format("Received %s in router %s", message.getNbr(), getSelf().path().name()));
      } else {
        unhandled(msg);
      }
    }

    public static class Message {
      private final int nbr;

      public Message(int nbr) {
        this.nbr = nbr;
      }

      public int getNbr() {
        return nbr;
      }

    }
  }

  public static void main(String... args) {
    Config config = ConfigFactory.parseString("akka.actor.deployment {\n" + "  /router {\n"
        + "    router = round-robin\n" + "    nr-of-instances = 5\n" + "  }\n" + "}\n");
    ActorSystem system = ActorSystem.create("Example", config);
    //#configurableRouting
    ActorRef router = system.actorOf(new Props(ExampleActor.class).withRouter(new FromConfig()), "router");
    //#configurableRouting
    for (int i = 1; i <= 10; i++) {
      router.tell(new ExampleActor.Message(i));
    }

    //#configurableRoutingWithResizer
    ActorRef router2 = system.actorOf(new Props(ExampleActor.class).withRouter(new FromConfig()), "router2");
    //#configurableRoutingWithResizer
    for (int i = 1; i <= 10; i++) {
      router2.tell(new ExampleActor.Message(i));
    }
  }
}