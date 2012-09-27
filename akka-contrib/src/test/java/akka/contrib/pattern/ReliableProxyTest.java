/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.pattern;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import scala.concurrent.util.Duration;
import scala.concurrent.util.FiniteDuration;
import akka.actor.Actor;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.FSM;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
import akka.testkit.TestProbe;

//#import
import akka.contrib.pattern.ReliableProxy;
//#import

public class ReliableProxyTest {

  private static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create();
  }

  @AfterClass
  public static void teardown() {
    system.shutdown();
  }

  @Test
  public void demonstrateUsage() {
    final TestProbe probe = TestProbe.apply(system);
    final ActorRef target = probe.ref();
    final ActorRef parent = system.actorOf(new Props(new UntypedActorFactory() {
      private static final long serialVersionUID = 1L;

      public Actor create() {
        return new UntypedActor() {

          //#demo-proxy
          final ActorRef proxy = getContext().actorOf(
              new Props(new UntypedActorFactory() {
                private static final long serialVersionUID = 1L;

                public Actor create() {
                  final FiniteDuration retry = Duration.create(100, "millis");
                  return new ReliableProxy(target, retry);
                }
              }));

          public void onReceive(Object msg) {
            if ("hello".equals(msg)) {
              proxy.tell("world!", getSelf());
            }
          }
          //#demo-proxy
        };
      }
    }));
    parent.tell("hello", null);
    probe.expectMsg("world!");
  }
  
  @Test
  public void demonstrateTransitions() {
    final ActorRef target = system.deadLetters();
    final ActorRef parent = system.actorOf(new Props(new UntypedActorFactory() {
      private static final long serialVersionUID = 1L;

      public Actor create() {
        return new UntypedActor() {

          //#demo-transition
          final ActorRef proxy = getContext().actorOf(
              new Props(new UntypedActorFactory() {
                private static final long serialVersionUID = 1L;

                public Actor create() {
                  final FiniteDuration retry = Duration.create(100, "millis");
                  return new ReliableProxy(target, retry);
                }
              }));
          ActorRef client = null;
          
          {
            proxy.tell(new FSM.SubscribeTransitionCallBack(getSelf()), getSelf());
          }

          public void onReceive(Object msg) {
            if ("hello".equals(msg)) {
              proxy.tell("world!", getSelf());
              client = getSender();
            } else if (msg instanceof FSM.CurrentState<?>) {
              // get initial state
            } else if (msg instanceof FSM.Transition<?>) {
              @SuppressWarnings("unchecked")
              final FSM.Transition<ReliableProxy.State> transition =
                  (FSM.Transition<ReliableProxy.State>) msg;
              assert transition.fsmRef().equals(proxy);
              if (transition.to().equals(ReliableProxy.idle())) {
                client.tell("done", getSelf());
              }
            }
          }
          //#demo-transition
        };
      }
    }));
    final TestProbe probe = TestProbe.apply(system);
    parent.tell("hello", probe.ref());
    probe.expectMsg("done");
  }
}
