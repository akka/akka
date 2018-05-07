/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.javadsl;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import akka.Done;
import akka.testkit.typed.javadsl.TestKitJunitResource;
import org.junit.ClassRule;
import org.scalatest.junit.JUnitSuite;
import scala.concurrent.duration.Duration;
import akka.util.Timeout;
import org.junit.Test;

import akka.actor.typed.*;

import static akka.actor.typed.javadsl.Behaviors.*;

public class WatchTest extends JUnitSuite {

  @ClassRule
  public static TestKitJunitResource testKit = new TestKitJunitResource();

  interface Message { }

  static final class RunTest implements Message {
    private final ActorRef<Done> replyTo;

    public RunTest(ActorRef<Done> replyTo) {
      this.replyTo = replyTo;
    }
  }

  static final class Stop {
  }

  static final class CustomTerminationMessage implements Message {
  }

  // final FiniteDuration fiveSeconds = FiniteDuration.create(5, TimeUnit.SECONDS);
  final Timeout timeout = new Timeout(Duration.create(5, TimeUnit.SECONDS));

  final Behavior<Stop> exitingActor = receive((ctx, msg) -> {
    System.out.println("Stopping!");
    return stopped();
  });

  private Behavior<RunTest> waitingForTermination(ActorRef<Done> replyWhenTerminated) {
    return receive(
      (ctx, msg) -> unhandled(),
      (ctx, sig) -> {
        if (sig instanceof Terminated) {
          replyWhenTerminated.tell(Done.getInstance());
        }
        return same();
      }
    );
  }

  private Behavior<Message> waitingForMessage(ActorRef<Done> replyWhenReceived) {
    return receive(
      (ctx, msg) -> {
        if (msg instanceof CustomTerminationMessage) {
          replyWhenReceived.tell(Done.getInstance());
          return same();
        } else {
          return unhandled();
        }
      }
    );
  }

  @Test
  public void shouldWatchTerminatingActor() throws Exception {
    Behavior<RunTest> exiting = Behaviors.receive(RunTest.class)
      .onMessage(RunTest.class, (ctx, msg) -> {
        ActorRef<Stop> watched = ctx.spawn(exitingActor, "exitingActor");
        ctx.watch(watched);
        watched.tell(new Stop());
        return waitingForTermination(msg.replyTo);
      }).build();
    ActorRef<RunTest> exitingRef = testKit.spawn(exiting);

    CompletionStage<Done> result = AskPattern.ask(exitingRef, RunTest::new, timeout, testKit.scheduler());
    result.toCompletableFuture().get(3, TimeUnit.SECONDS);
  }

  @Test
  public void shouldWatchWithCustomMessage() throws Exception {
    Behavior<Message> exiting = Behaviors.receive(Message.class)
      .onMessage(RunTest.class, (ctx, msg) -> {
        ActorRef<Stop> watched = ctx.spawn(exitingActor, "exitingActor");
        ctx.watchWith(watched, new CustomTerminationMessage());
        watched.tell(new Stop());
        return waitingForMessage(msg.replyTo);
      }).build();
    ActorRef<Message> exitingRef = testKit.spawn(exiting);

    // Not sure why this does not compile without an explicit cast?
    // system.tell(new RunTest());
    CompletionStage<Done> result = AskPattern.ask(exitingRef, RunTest::new, timeout, testKit.scheduler());
    result.toCompletableFuture().get(3, TimeUnit.SECONDS);

  }
}
