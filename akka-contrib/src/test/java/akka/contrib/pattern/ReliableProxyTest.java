/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.pattern;

import java.util.concurrent.TimeUnit;

import akka.testkit.AkkaJUnitActorSystemResource;

import org.junit.ClassRule;
import org.junit.Test;

import scala.concurrent.duration.Duration;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.FSM;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.testkit.TestProbe;

//#import
import akka.contrib.pattern.ReliableProxy;


//#import

public class ReliableProxyTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("ReliableProxyTest");

  private final ActorSystem system = actorSystemResource.getSystem();

  static//#demo-proxy
  public class ProxyParent extends UntypedActor {
    private final ActorRef proxy;

    public ProxyParent(ActorRef target) {
      proxy = getContext().actorOf(
          ReliableProxy.props(target,
              Duration.create(100, TimeUnit.MILLISECONDS)));
    }

    public void onReceive(Object msg) {
      if ("hello".equals(msg)) {
        proxy.tell("world!", getSelf());
      }
    }
  }

  //#demo-proxy

  static//#demo-transition
  public class ProxyTransitionParent extends UntypedActor {
    private final ActorRef proxy;
    private ActorRef client = null;

    public ProxyTransitionParent(ActorRef target) {
      proxy = getContext().actorOf(
          ReliableProxy.props(target,
              Duration.create(100, TimeUnit.MILLISECONDS)));
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
  }

  //#demo-transition

  @Test
  public void demonstrateUsage() {
    final TestProbe probe = TestProbe.apply(system);
    final ActorRef target = probe.ref();
    final ActorRef parent = system.actorOf(Props.create(ProxyParent.class, target));
    parent.tell("hello", null);
    probe.expectMsg("world!");
  }

  @Test
  public void demonstrateTransitions() {
    final ActorRef target = system.deadLetters();
    final ActorRef parent = system.actorOf(Props.create(ProxyTransitionParent.class, target));
    final TestProbe probe = TestProbe.apply(system);
    parent.tell("hello", probe.ref());
    probe.expectMsg("done");
  }
}
