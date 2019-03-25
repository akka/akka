/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.contrib.pattern;

import java.util.concurrent.TimeUnit;

import akka.actor.*;
import akka.testkit.AkkaJUnitActorSystemResource;

import org.junit.ClassRule;
import org.junit.Test;

import org.scalatestplus.junit.JUnitSuite;
import scala.concurrent.duration.Duration;
import akka.testkit.TestProbe;

// #import
import akka.contrib.pattern.ReliableProxy;

// #import

public class ReliableProxyTest extends JUnitSuite {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
      new AkkaJUnitActorSystemResource("ReliableProxyTest");

  private final ActorSystem system = actorSystemResource.getSystem();

  public // #demo-proxy
  static class ProxyParent extends AbstractActor {
    private final ActorRef proxy;

    public ProxyParent(ActorPath targetPath) {
      proxy =
          getContext()
              .actorOf(
                  ReliableProxy.props(targetPath, Duration.create(100, TimeUnit.MILLISECONDS)));
    }

    @Override
    public Receive createReceive() {
      return receiveBuilder()
          .matchEquals(
              "hello",
              m -> {
                proxy.tell("world!", self());
              })
          .build();
    }
  }

  // #demo-proxy

  public // #demo-transition
  static class ProxyTransitionParent extends AbstractActor {
    private final ActorRef proxy;
    private ActorRef client = null;

    public ProxyTransitionParent(ActorPath targetPath) {
      proxy =
          getContext()
              .actorOf(
                  ReliableProxy.props(targetPath, Duration.create(100, TimeUnit.MILLISECONDS)));
      proxy.tell(new FSM.SubscribeTransitionCallBack(getSelf()), getSelf());
    }

    @Override
    public Receive createReceive() {
      return receiveBuilder()
          .matchEquals(
              "hello",
              message -> {
                proxy.tell("world!", self());
                client = sender();
              })
          .matchUnchecked(
              FSM.CurrentState.class,
              (FSM.CurrentState<ReliableProxy.State> state) -> {
                // get initial state
              })
          .matchUnchecked(
              FSM.Transition.class,
              (FSM.Transition<ReliableProxy.State> transition) -> {
                assert transition.fsmRef().equals(proxy);
                if (transition.from().equals(ReliableProxy.active())
                    && transition.to().equals(ReliableProxy.idle())) {
                  client.tell("done", self());
                }
              })
          .build();
    }
  }

  // #demo-transition

  @Test
  public void demonstrateUsage() {
    final TestProbe probe = TestProbe.apply(system);
    final ActorRef target = probe.ref();
    final ActorRef parent = system.actorOf(Props.create(ProxyParent.class, target.path()));
    parent.tell("hello", null);
    probe.expectMsg("world!");
  }

  @Test
  public void demonstrateTransitions() {
    final ActorRef target = TestProbe.apply(system).ref();
    final ActorRef parent =
        system.actorOf(Props.create(ProxyTransitionParent.class, target.path()));
    final TestProbe probe = TestProbe.apply(system);
    parent.tell("hello", probe.ref());
    probe.expectMsg("done");
  }
}
