/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.javadsl;

import akka.actor.testkit.typed.javadsl.LogCapturing;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.pattern.ReplyWithStatus;
import akka.testkit.AkkaSpec;
import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;

import java.time.Duration;

public class ActorContextAskTest extends JUnitSuite {

  @ClassRule
  public static final TestKitJunitResource testKit = new TestKitJunitResource(AkkaSpec.testConf());

  @Rule public final LogCapturing logCapturing = new LogCapturing();

  static class Ping {
    final ActorRef<Pong> replyTo;

    public Ping(ActorRef<Pong> replyTo) {
      this.replyTo = replyTo;
    }
  }

  static class Pong {}

  @Test
  public void provideASafeAsk() {
    final Behavior<Ping> pingPongBehavior =
        Behaviors.receive(
            (ActorContext<Ping> context, Ping message) -> {
              message.replyTo.tell(new Pong());
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

  static class PingWithStatus {
    final ActorRef<ReplyWithStatus<Pong>> replyTo;

    public PingWithStatus(ActorRef<ReplyWithStatus<Pong>> replyTo) {
      this.replyTo = replyTo;
    }
  }

  @Test
  public void provideAskWithStatus() {
    final TestProbe<Object> probe = testKit.createTestProbe();

    testKit.spawn(
        Behaviors.<Pong>setup(
            context -> {
              context.askWithStatus(
                  Pong.class,
                  probe.getRef(),
                  Duration.ofSeconds(3),
                  PingWithStatus::new,
                  (pong, failure) -> {
                    if (pong != null) return pong;
                    else throw new RuntimeException(failure);
                  });

              return Behaviors.receive(Pong.class)
                  .onAnyMessage(
                      pong -> {
                        probe.ref().tell("got pong");
                        return Behaviors.same();
                      })
                  .build();
            }));

    ActorRef<ReplyWithStatus<Pong>> replyTo =
        probe.expectMessageClass(PingWithStatus.class).replyTo;

    replyTo.tell(ReplyWithStatus.success(new Pong()));
    probe.expectMessage("got pong");
  }
}
