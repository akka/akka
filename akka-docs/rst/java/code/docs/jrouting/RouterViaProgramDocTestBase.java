/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.jrouting;

import java.util.Arrays;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Kill;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.actor.Terminated;
import akka.routing.Broadcast;
import akka.routing.RoundRobinRouter;
import akka.testkit.JavaTestKit;
import docs.jrouting.RouterViaProgramExample.ExampleActor;
import docs.routing.RouterViaProgramDocSpec.Echo;

public class RouterViaProgramDocTestBase {

  static ActorSystem system;
  
  @BeforeClass
  public static void setup() {
    system = ActorSystem.create();
  }
  
  @AfterClass
  public static void teardown() {
    system.shutdown();
  }

  private static class JavaTestKitWithSelf extends JavaTestKit {
    public JavaTestKitWithSelf(ActorSystem system) {
      super(system);
    }
    /**
     * Wrap `getRef()` so our examples look like they're within a normal actor.
     */
    public ActorRef getSelf() {
      return getRef();
    }
  }

  @SuppressWarnings("unused")
  @Test
  public void demonstrateRouteesFromPaths() {
    new JavaTestKit(system) {{
      //#programmaticRoutingRouteePaths      
      ActorRef actor1 = system.actorOf(Props.create(ExampleActor.class), "actor1");
      ActorRef actor2 = system.actorOf(Props.create(ExampleActor.class), "actor2");
      ActorRef actor3 = system.actorOf(Props.create(ExampleActor.class), "actor3");
      Iterable<String> routees = Arrays.asList(
        new String[] { "/user/actor1", "/user/actor2", "/user/actor3" });
      ActorRef router = system.actorOf(
        Props.empty().withRouter(new RoundRobinRouter(routees)));
      //#programmaticRoutingRouteePaths
      for (int i = 1; i <= 6; i++) {
        router.tell(new ExampleActor.Message(i), null);
      }
    }};
  }

  @Test
  public void demonstrateBroadcast() {
    new JavaTestKitWithSelf(system) {{
      ActorRef router = system.actorOf(Props.create(Echo.class).withRouter(new RoundRobinRouter(5)));
      //#broadcastDavyJonesWarning
      router.tell(new Broadcast("Watch out for Davy Jones' locker"), getSelf());
      //#broadcastDavyJonesWarning
      receiveN(5, duration("5 seconds"));
    }};
  }

  @Test
  public void demonstratePoisonPill() {
    new JavaTestKitWithSelf(system) {{
      ActorRef router = system.actorOf(Props.create(Echo.class).withRouter(new RoundRobinRouter(5)));
      watch(router);
      //#poisonPill
      router.tell(PoisonPill.getInstance(), getSelf());
      //#poisonPill
      expectMsgClass(Terminated.class);
    }};
  }

  @Test
  public void demonstrateBroadcastOfPoisonPill() {
    new JavaTestKitWithSelf(system) {{
      ActorRef router = system.actorOf(Props.create(Echo.class).withRouter(new RoundRobinRouter(5)));
      watch(router);
      //#broadcastPoisonPill
      router.tell(new Broadcast(PoisonPill.getInstance()), getSelf());
      //#broadcastPoisonPill
        expectMsgClass(Terminated.class);
    }};
  }

  @Test
  public void demonstrateKill() {
    new JavaTestKitWithSelf(system) {{
      ActorRef router = system.actorOf(Props.create(Echo.class).withRouter(new RoundRobinRouter(5)));
      watch(router);
      //#kill
      router.tell(Kill.getInstance(), getSelf());
      //#kill
      expectMsgClass(Terminated.class);
    }};
  }

  @Test
  public void demonstrateBroadcastOfKill() {
    new JavaTestKitWithSelf(system) {{
      ActorRef router = system.actorOf(Props.create(Echo.class).withRouter(new RoundRobinRouter(5)));
      watch(router);
      //#broadcastKill
      router.tell(new Broadcast(Kill.getInstance()), getSelf());
      //#broadcastKill
      expectMsgClass(Terminated.class);
    }};
  }

}
