/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.transactor;

import static org.junit.Assert.*;

import akka.testkit.JavaTestKit;
import org.junit.Test;

//#imports
import akka.actor.*;
import scala.concurrent.Await;
import static akka.pattern.Patterns.ask;
import akka.transactor.Coordinated;
import akka.util.Timeout;
import static java.util.concurrent.TimeUnit.SECONDS;
//#imports

public class TransactorDocTest {

  @Test
  public void coordinatedExample() throws Exception {
    //#coordinated-example
    ActorSystem system = ActorSystem.create("CoordinatedExample");

    ActorRef counter1 = system.actorOf(Props.create(CoordinatedCounter.class));
    ActorRef counter2 = system.actorOf(Props.create(CoordinatedCounter.class));

    Timeout timeout = new Timeout(5, SECONDS);

    counter1.tell(new Coordinated(new Increment(counter2), timeout), ActorRef.noSender());

    Integer count = (Integer) Await.result(
      ask(counter1, "GetCount", timeout), timeout.duration());
    //#coordinated-example

    assertEquals(count, new Integer(1));

    JavaTestKit.shutdownActorSystem(system);
  }

  @Test
  public void coordinatedApi() {
    //#create-coordinated
    Timeout timeout = new Timeout(5, SECONDS);
    Coordinated coordinated = new Coordinated(timeout);
    //#create-coordinated

    ActorSystem system = ActorSystem.create("CoordinatedApi");
    ActorRef actor = system.actorOf(Props.create(Coordinator.class));

    //#send-coordinated
    actor.tell(new Coordinated(new Message(), timeout), ActorRef.noSender());
    //#send-coordinated

    //#include-coordinated
    actor.tell(coordinated.coordinate(new Message()), ActorRef.noSender());
    //#include-coordinated

    coordinated.await();

    JavaTestKit.shutdownActorSystem(system);
  }

  @Test
  public void counterTransactor() throws Exception {
    ActorSystem system = ActorSystem.create("CounterTransactor");
    ActorRef counter = system.actorOf(Props.create(Counter.class));

    Timeout timeout = new Timeout(5, SECONDS);
    Coordinated coordinated = new Coordinated(timeout);
    counter.tell(coordinated.coordinate(new Increment()), ActorRef.noSender());
    coordinated.await();

    Integer count = (Integer) Await.result(ask(counter, "GetCount", timeout), timeout.duration());
    assertEquals(count, new Integer(1));

    JavaTestKit.shutdownActorSystem(system);
  }

  @Test
  public void friendlyCounterTransactor() throws Exception {
    ActorSystem system = ActorSystem.create("FriendlyCounterTransactor");
    ActorRef friend = system.actorOf(Props.create(Counter.class));
    ActorRef friendlyCounter = system.actorOf(Props.create(FriendlyCounter.class));

    Timeout timeout = new Timeout(5, SECONDS);
    Coordinated coordinated = new Coordinated(timeout);
    friendlyCounter.tell(coordinated.coordinate(new Increment(friend)), ActorRef.noSender());
    coordinated.await();

    Integer count1 = (Integer) Await.result(ask(friendlyCounter, "GetCount", timeout), timeout.duration());
    assertEquals(count1, new Integer(1));

    Integer count2 = (Integer) Await.result(ask(friend, "GetCount", timeout), timeout.duration());
    assertEquals(count2, new Integer(1));

    JavaTestKit.shutdownActorSystem(system);
  }
}
