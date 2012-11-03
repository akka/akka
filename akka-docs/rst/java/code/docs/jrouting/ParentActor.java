/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.jrouting;

import akka.routing.ScatterGatherFirstCompletedRouter;
import akka.routing.BroadcastRouter;
import akka.routing.RandomRouter;
import akka.routing.RoundRobinRouter;
import akka.routing.SmallestMailboxRouter;
import akka.actor.UntypedActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import scala.concurrent.duration.Duration;
import akka.util.Timeout;
import scala.concurrent.Future;
import scala.concurrent.Await;

//#parentActor
public class ParentActor extends UntypedActor {
  public void onReceive(Object msg) throws Exception {
    if (msg.equals("rrr")) {
      //#roundRobinRouter
      ActorRef roundRobinRouter = getContext().actorOf(
          new Props(PrintlnActor.class).withRouter(new RoundRobinRouter(5)),
        "router");
      for (int i = 1; i <= 10; i++) {
        roundRobinRouter.tell(i, getSelf());
      }
      //#roundRobinRouter
    } else if (msg.equals("rr")) {
      //#randomRouter
      ActorRef randomRouter = getContext().actorOf(
        new Props(PrintlnActor.class).withRouter(new RandomRouter(5)),
          "router");
      for (int i = 1; i <= 10; i++) {
        randomRouter.tell(i, getSelf());
      }
      //#randomRouter
    } else if (msg.equals("smr")) {
      //#smallestMailboxRouter
      ActorRef smallestMailboxRouter = getContext().actorOf(
          new Props(PrintlnActor.class).withRouter(new SmallestMailboxRouter(5)),
        "router");
      for (int i = 1; i <= 10; i++) {
        smallestMailboxRouter.tell(i, getSelf());
      }
      //#smallestMailboxRouter
    } else if (msg.equals("br")) {
      //#broadcastRouter
      ActorRef broadcastRouter = getContext().actorOf(
        new Props(PrintlnActor.class).withRouter(new BroadcastRouter(5)), "router");
      broadcastRouter.tell("this is a broadcast message", getSelf());
      //#broadcastRouter
    } else if (msg.equals("sgfcr")) {
      //#scatterGatherFirstCompletedRouter
      ActorRef scatterGatherFirstCompletedRouter = getContext().actorOf(
          new Props(FibonacciActor.class).withRouter(
            new ScatterGatherFirstCompletedRouter(5, Duration.create(2, "seconds"))),
        "router");
      Timeout timeout = new Timeout(Duration.create(5, "seconds"));
      Future<Object> futureResult = akka.pattern.Patterns.ask(
        scatterGatherFirstCompletedRouter, new FibonacciActor.FibonacciNumber(10),
        timeout);
      int result = (Integer) Await.result(futureResult, timeout.duration());
      //#scatterGatherFirstCompletedRouter
      System.out.println(
        String.format("The result of calculating Fibonacci for 10 is %d", result));
    } else {
      unhandled(msg);
    }
  }
}

//#parentActor