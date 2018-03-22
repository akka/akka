/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.javadsl;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.testkit.AkkaSpec;
import akka.testkit.typed.javadsl.TestKitJunitResource;
import akka.testkit.typed.javadsl.TestProbe;
import akka.util.Timeout;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import java.util.concurrent.TimeUnit;

public class ActorContextAskTest extends JUnitSuite {

  @ClassRule
  public static final TestKitJunitResource testKit = new TestKitJunitResource(AkkaSpec.testConf());

  static class Ping {
    final ActorRef<Pong> respondTo;
    public Ping(ActorRef<Pong> respondTo) {
      this.respondTo = respondTo;
    }
  }
  static class Pong { }

  @Test
  public void provideASafeAsk() {
    final Behavior<Ping> pingPongBehavior = Behaviors.receive((ActorContext<Ping> context, Ping message) -> {
      message.respondTo.tell(new Pong());
      return Behaviors.same();
    });

    final ActorRef<Ping> pingPong = testKit.spawn(pingPongBehavior);
    final TestProbe<Object> probe = testKit.createTestProbe();

    final Behavior<Object> snitch = Behaviors.setup((ActorContext<Object> ctx) -> {
      ctx.ask(Pong.class,
          pingPong,
          new Timeout(3, TimeUnit.SECONDS),
          (ActorRef<Pong> ref) -> new Ping(ref),
          (pong, exception) -> {
            if (pong != null) return pong;
            else return exception;
          });

      return Behaviors.receive((ActorContext<Object> context, Object message) -> {
        probe.ref().tell(message);
        return Behaviors.same();
      });
    });

    testKit.spawn(snitch);

    probe.expectMessageClass(Pong.class);
  }


}
