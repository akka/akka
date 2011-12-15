.. _routing-java:

Routing (Java)
==============

UntypedDispatcher
-----------------

An UntypedDispatcher is an actor that routes incoming messages to outbound actors.

.. code-block:: java

  import static akka.actor.Actors.*;
  import akka.actor.*;
  import akka.routing.*;

  //A Pinger is an UntypedActor that prints "Pinger: <message>"
  class Pinger extends UntypedActor {
    public void onReceive(Object message) throws Exception {
      System.out.println("Pinger: " + message);
    }
  }

  //A Ponger is an UntypedActor that prints "Ponger: <message>"
  class Ponger extends UntypedActor {
    public void onReceive(Object message) throws Exception {
      System.out.println("Ponger: " + message);
    }
  }

  public class MyRouter extends UntypedRouter {
    private ActorRef pinger = actorOf(new Props(Pinger.class));
    private ActorRef ponger = actorOf(new Props(Ponger.class));

    //Route Ping-messages to the pinger, and Pong-messages to the ponger
    public ActorRef route(Object message) {
      if("Ping".equals(message)) return pinger;
      else if("Pong".equals(message)) return ponger;
      else throw new IllegalArgumentException("I do not understand " + message);
    }
  }

  ActorRef router = actorOf(new Props(MyRouter.class));
  router.tell("Ping"); //Prints "Pinger: Ping"
  router.tell("Pong"); //Prints "Ponger: Pong"

UntypedLoadBalancer
-------------------

An UntypedLoadBalancer is an actor that forwards messages it receives to a boundless sequence of destination actors.

.. code-block:: java

  import static akka.actor.Actors.*;
  import akka.actor.*;
  import akka.routing.*;
  import static java.util.Arrays.asList;

  //A Pinger is an UntypedActor that prints "Pinger: <message>"
  class Pinger extends UntypedActor {
    public void onReceive(Object message) throws Exception {
      System.out.println("Pinger: " + message);
    }
  }

  //A Ponger is an UntypedActor that prints "Ponger: <message>"
  class Ponger extends UntypedActor {
    public void onReceive(Object message) throws Exception {
      System.out.println("Ponger: " + message);
    }
  }

  //Our load balancer, sends messages to a pinger, then a ponger, rinse and repeat.
  public class MyLoadBalancer extends UntypedLoadBalancer {
    private InfiniteIterator<ActorRef> actors = new CyclicIterator<ActorRef>(asList(
      actorOf(new Props(Pinger.class)),
      actorOf(new Props(Ponger.class))
    ));

    public InfiniteIterator<ActorRef> seq() {
      return actors;
    }
  }

  ActorRef balancer = actorOf(new Props(MyLoadBalancer.class));
  balancer.tell("Pong"); //Prints "Pinger: Pong"
  balancer.tell("Ping"); //Prints "Ponger: Ping"
  balancer.tell("Ping"); //Prints "Pinger: Ping"
  balancer.tell("Pong"); //Prints "Ponger: Pong

You can also send a 'new Routing.Broadcast(msg)' message to the router to have it be broadcasted out to all the actors it represents.

.. code-block:: java

  balancer.tell(new Routing.Broadcast(new PoisonPill()));

