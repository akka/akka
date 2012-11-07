/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.jrouting;

import static akka.pattern.Patterns.ask;
import static docs.jrouting.CustomRouterDocTestBase.Message.DemocratCountResult;
import static docs.jrouting.CustomRouterDocTestBase.Message.DemocratVote;
import static docs.jrouting.CustomRouterDocTestBase.Message.RepublicanCountResult;
import static docs.jrouting.CustomRouterDocTestBase.Message.RepublicanVote;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.OneForOneStrategy;
import akka.actor.Props;
import akka.actor.SupervisorStrategy;
import akka.actor.UntypedActor;
import akka.dispatch.Dispatchers;
import akka.routing.CustomRoute;
import akka.routing.CustomRouterConfig;
import akka.routing.Destination;
import akka.routing.RoundRobinRouter;
import akka.routing.RouteeProvider;
import akka.testkit.AkkaSpec;
import akka.util.Timeout;

public class CustomRouterDocTestBase {

  ActorSystem system;

  @Before
  public void setUp() {
    system = ActorSystem.create("MySystem", AkkaSpec.testConf());
  }

  @After
  public void tearDown() {
    system.shutdown();
  }
  
  public static class MyActor extends UntypedActor {
    @Override public void onReceive(Object o) {}
  }
  
  @Test
  public void demonstrateDispatchers() {
    //#dispatchers
    final ActorRef router = system.actorOf(new Props(MyActor.class)
      // “head” router will run on "head" dispatcher
      .withRouter(new RoundRobinRouter(5).withDispatcher("head"))
      // MyActor “workers” will run on "workers" dispatcher
      .withDispatcher("workers"));
    //#dispatchers
  }
  
  @Test
  public void demonstrateSupervisor() {
    //#supervision
    final SupervisorStrategy strategy =
      new OneForOneStrategy(5, Duration.create("1 minute"),
         Collections.<Class<? extends Throwable>>singletonList(Exception.class));
    final ActorRef router = system.actorOf(new Props(MyActor.class)
        .withRouter(new RoundRobinRouter(5).withSupervisorStrategy(strategy)));
    //#supervision
  }

  //#crTest
  @Test
  public void countVotesAsIntendedNotAsInFlorida() throws Exception {
    ActorRef routedActor = system.actorOf(
      new Props().withRouter(new VoteCountRouter()));
    routedActor.tell(DemocratVote, null);
    routedActor.tell(DemocratVote, null);
    routedActor.tell(RepublicanVote, null);
    routedActor.tell(DemocratVote, null);
    routedActor.tell(RepublicanVote, null);
    Timeout timeout = new Timeout(Duration.create(1, "seconds"));
    Future<Object> democratsResult =
      ask(routedActor, DemocratCountResult, timeout);
    Future<Object> republicansResult =
      ask(routedActor, RepublicanCountResult, timeout);

    assertEquals(3, Await.result(democratsResult, timeout.duration()));
    assertEquals(2, Await.result(republicansResult, timeout.duration()));
  }

  //#crTest

  //#CustomRouter
  //#crMessages
  enum Message {
    DemocratVote, DemocratCountResult, RepublicanVote, RepublicanCountResult
  }

  //#crMessages
  //#CustomRouter
  static
  //#CustomRouter
  //#crActors
  public class DemocratActor extends UntypedActor {
    int counter = 0;

    public void onReceive(Object msg) {
      switch ((Message) msg) {
      case DemocratVote:
        counter++;
        break;
      case DemocratCountResult:
        getSender().tell(counter, getSelf());
        break;
      default:
        unhandled(msg);
      }
    }
  }

  //#crActors
  //#CustomRouter
  static
  //#CustomRouter
  //#crActors
  public class RepublicanActor extends UntypedActor {
    int counter = 0;

    public void onReceive(Object msg) {
      switch ((Message) msg) {
      case RepublicanVote:
        counter++;
        break;
      case RepublicanCountResult:
        getSender().tell(counter, getSelf());
        break;
      default:
        unhandled(msg);
      }
    }
  }

  //#crActors
  //#CustomRouter
  static
  //#CustomRouter
  //#crRouter
  public class VoteCountRouter extends CustomRouterConfig {
    
    @Override public String routerDispatcher() {
      return Dispatchers.DefaultDispatcherId();
    }
    
    @Override public SupervisorStrategy supervisorStrategy() {
      return SupervisorStrategy.defaultStrategy();
    }

    //#crRoute
    @Override
    public CustomRoute createCustomRoute(RouteeProvider routeeProvider) {
      final ActorRef democratActor =
        routeeProvider.context().actorOf(new Props(DemocratActor.class), "d");
      final ActorRef republicanActor =
        routeeProvider.context().actorOf(new Props(RepublicanActor.class), "r");
      List<ActorRef> routees =
        Arrays.asList(new ActorRef[] { democratActor, republicanActor });

      //#crRegisterRoutees
      routeeProvider.registerRoutees(routees);
      //#crRegisterRoutees

      //#crRoutingLogic
      return new CustomRoute() {
        @Override
        public scala.collection.immutable.Seq<Destination> destinationsFor(ActorRef sender, Object msg) {
          switch ((Message) msg) {
          case DemocratVote:
          case DemocratCountResult:
            return akka.japi.Util.immutableSingletonSeq(new Destination(sender, democratActor));
          case RepublicanVote:
          case RepublicanCountResult:
            return akka.japi.Util.immutableSingletonSeq(new Destination(sender, republicanActor));
          default:
            throw new IllegalArgumentException("Unknown message: " + msg);
          }
        }
      };
      //#crRoutingLogic
    }
    //#crRoute

  }

  //#crRouter
  //#CustomRouter
}
