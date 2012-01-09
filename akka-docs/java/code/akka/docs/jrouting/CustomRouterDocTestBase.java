/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.jrouting;

import java.util.List;
import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

import akka.actor.*;
import akka.routing.*;
import akka.util.Duration;
import akka.util.Timeout;
import akka.dispatch.Await;
import akka.dispatch.Future;
import akka.testkit.AkkaSpec;
import com.typesafe.config.ConfigFactory;

import static akka.docs.jrouting.CustomRouterDocTestBase.DemocratActor;
import static akka.docs.jrouting.CustomRouterDocTestBase.RepublicanActor;
import static akka.docs.jrouting.CustomRouterDocTestBase.Message.*;

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

  //#crTest
  @Test
  public void countVotesAsIntendedNotAsInFlorida() {
    ActorRef routedActor = system.actorOf(new Props().withRouter(new VoteCountRouter()));
    routedActor.tell(DemocratVote);
    routedActor.tell(DemocratVote);
    routedActor.tell(RepublicanVote);
    routedActor.tell(DemocratVote);
    routedActor.tell(RepublicanVote);
    Timeout timeout = new Timeout(Duration.parse("1 seconds"));
    Future<Object> democratsResult = routedActor.ask(DemocratCountResult, timeout);
    Future<Object> republicansResult = routedActor.ask(RepublicanCountResult, timeout);

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

  //#crActors
  public static class DemocratActor extends UntypedActor {
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

  public static class RepublicanActor extends UntypedActor {
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

  //#crRouter
  public static class VoteCountRouter extends CustomRouterConfig {

    //#crRoute
    @Override
    public CustomRoute createCustomRoute(Props props, ActorContext context, RoutedActorRef ref) {
      final ActorRef democratActor = context.actorOf(new Props(DemocratActor.class), "d");
      final ActorRef republicanActor = context.actorOf(new Props(RepublicanActor.class), "r");
      List<ActorRef> routees = Arrays.asList(new ActorRef[] { democratActor, republicanActor });

      //#crRegisterRoutees
      registerRoutees(context, routees);
      //#crRegisterRoutees

      //#crRoutingLogic
      return new CustomRoute() {
        @Override
        public Iterable<Destination> destinationsFor(ActorRef sender, Object msg) {
          switch ((Message) msg) {
          case DemocratVote:
          case DemocratCountResult:
            return Arrays.asList(new Destination[] { new Destination(sender, democratActor) });
          case RepublicanVote:
          case RepublicanCountResult:
            return Arrays.asList(new Destination[] { new Destination(sender, republicanActor) });
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
