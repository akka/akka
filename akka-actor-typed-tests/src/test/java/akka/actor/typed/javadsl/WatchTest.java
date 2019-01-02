/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.javadsl;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import akka.Done;
import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import org.junit.ClassRule;
import org.scalatest.junit.JUnitSuite;
import org.junit.Test;

import akka.actor.typed.*;

import java.time.Duration;

import static akka.Done.done;
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

  final Duration timeout = Duration.ofSeconds(5);

  final Behavior<Stop> exitingActor = receive((context, message) -> {
    System.out.println("Stopping!");
    return stopped();
  });

  private Behavior<RunTest> waitingForTermination(ActorRef<Done> replyWhenTerminated) {
    return receive(
      (context, message) -> unhandled(),
      (context, sig) -> {
        if (sig instanceof Terminated) {
          replyWhenTerminated.tell(done());
        }
        return same();
      }
    );
  }

  private Behavior<Message> waitingForMessage(ActorRef<Done> replyWhenReceived) {
    return receive(
      (context, message) -> {
        if (message instanceof CustomTerminationMessage) {
          replyWhenReceived.tell(done());
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
      .onMessage(RunTest.class, (context, message) -> {
        ActorRef<Stop> watched = context.spawn(exitingActor, "exitingActor");
        context.watch(watched);
        watched.tell(new Stop());
        return waitingForTermination(message.replyTo);
      }).build();
    ActorRef<RunTest> exitingRef = testKit.spawn(exiting);

    CompletionStage<Done> result = AskPattern.ask(exitingRef, RunTest::new, timeout, testKit.scheduler());
    result.toCompletableFuture().get(3, TimeUnit.SECONDS);
  }

  @Test
  public void shouldWatchWithCustomMessage() throws Exception {
    Behavior<Message> exiting = Behaviors.receive(Message.class)
      .onMessage(RunTest.class, (context, message) -> {
        ActorRef<Stop> watched = context.spawn(exitingActor, "exitingActor");
        context.watchWith(watched, new CustomTerminationMessage());
        watched.tell(new Stop());
        return waitingForMessage(message.replyTo);
      }).build();
    ActorRef<Message> exitingRef = testKit.spawn(exiting);

    // Not sure why this does not compile without an explicit cast?
    // system.tell(new RunTest());
    CompletionStage<Done> result = AskPattern.ask(exitingRef, RunTest::new, timeout, testKit.scheduler());
    result.toCompletableFuture().get(3, TimeUnit.SECONDS);

  }
}
