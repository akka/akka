/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.jrouting;

import akka.routing.RoundRobinRouter;
import akka.routing.DefaultResizer;
import akka.remote.routing.RemoteRouterConfig;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.actor.ActorSystem;
import akka.actor.Address;
import akka.actor.AddressFromURIString;
import java.util.Arrays;

public class RouterViaProgramExample {

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
    ActorSystem system = ActorSystem.create("RPE");
    //#programmaticRoutingNrOfInstances
    int nrOfInstances = 5;
    ActorRef router1 = system.actorOf(new Props(ExampleActor.class).withRouter(new RoundRobinRouter(nrOfInstances)));
    //#programmaticRoutingNrOfInstances
    for (int i = 1; i <= 6; i++) {
      router1.tell(new ExampleActor.Message(i));
    }

    //#programmaticRoutingRoutees
    ActorRef actor1 = system.actorOf(new Props(ExampleActor.class));
    ActorRef actor2 = system.actorOf(new Props(ExampleActor.class));
    ActorRef actor3 = system.actorOf(new Props(ExampleActor.class));
    Iterable<ActorRef> routees = Arrays.asList(new ActorRef[] { actor1, actor2, actor3 });
    ActorRef router2 = system.actorOf(new Props().withRouter(RoundRobinRouter.create(routees)));
    //#programmaticRoutingRoutees
    for (int i = 1; i <= 6; i++) {
      router2.tell(new ExampleActor.Message(i));
    }

    //#programmaticRoutingWithResizer
    int lowerBound = 2;
    int upperBound = 15;
    DefaultResizer resizer = new DefaultResizer(lowerBound, upperBound);
    ActorRef router3 = system.actorOf(new Props(ExampleActor.class).withRouter(new RoundRobinRouter(nrOfInstances)));
    //#programmaticRoutingWithResizer
    for (int i = 1; i <= 6; i++) {
      router3.tell(new ExampleActor.Message(i));
    }
    
    //#remoteRoutees
    Address addr1 = new Address("akka", "remotesys", "otherhost", 1234);
    Address addr2 = AddressFromURIString.parse("akka://othersys@anotherhost:1234");
    Address[] addresses = new Address[] { addr1, addr2 };
    ActorRef routerRemote = system.actorOf(new Props(ExampleActor.class)
      .withRouter(new RemoteRouterConfig(new RoundRobinRouter(5), addresses)));
    //#remoteRoutees
  }
  
  private class CompileCheckJavaDocsForRouting extends UntypedActor {

    @Override
    public void onReceive(Object o) {
      //#reply-with-parent
      getSender().tell("reply", getContext().parent()); // replies go to router
      //#reply-with-parent
      //#reply-with-self
      getSender().tell("reply", getSelf()); // replies go to this actor
      //#reply-with-self
    }
    
  }
}