/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.javadsl;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.testkit.AkkaSpec;
import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.util.Timeout;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import java.time.Duration;
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

  static class Pong {}

  @Test
  public void provideASafeAsk() {
    final Behavior<Ping> pingPongBehavior =
        Behaviors.receive(
            (ActorContext<Ping> context, Ping message) -> {
              message.respondTo.tell(new Pong());
              return Behaviors.same();
            });

    final ActorRef<Ping> pingPong = testKit.spawn(pingPongBehavior);
    final TestProbe<Object> probe = testKit.createTestProbe();

    final Behavior<Object> snitch =
        Behaviors.setup(
            (ActorContext<Object> context) -> {
              context.ask(
                  Pong.class,
                  pingPong,
                  Duration.ofSeconds(3),
                  (ActorRef<Pong> ref) -> new Ping(ref),
                  (pong, exception) -> {
                    if (pong != null) return pong;
                    else return exception;
                  });

              return Behaviors.receiveMessage(
                  (Object message) -> {
                    probe.ref().tell(message);
                    return Behaviors.same();
                  });
            });

    testKit.spawn(snitch);

    probe.expectMessageClass(Pong.class);
  }
}
