/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.javadsl;

import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.Behavior;
import akka.actor.typed.Props;
import com.typesafe.config.ConfigFactory;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.hamcrest.CoreMatchers.startsWith;
import static org.junit.Assert.*;

public final class ActorContextPipeToSelfTest extends JUnitSuite {

  @ClassRule
  public static final TestKitJunitResource testKit =
      new TestKitJunitResource(
          ConfigFactory.parseString(
              "pipe-to-self-spec-dispatcher.executor = thread-pool-executor\n"
                  + "pipe-to-self-spec-dispatcher.type = PinnedDispatcher\n"));

  static final class Msg {
    final String response;
    final String selfName;
    final String threadName;

    Msg(final String response, final String selfName, final String threadName) {
      this.response = response;
      this.selfName = selfName;
      this.threadName = threadName;
    }
  }

  @Test
  public void handlesSuccess() {
    assertEquals("ok: hi", responseFrom(CompletableFuture.completedFuture("hi")));
  }

  @Test
  public void handlesFailure() {
    assertEquals("ko: boom", responseFrom(failedFuture(new RuntimeException("boom"))));
  }

  private CompletableFuture<String> failedFuture(final Throwable ex) {
    final CompletableFuture<String> future = new CompletableFuture<>();
    future.completeExceptionally(ex);
    return future;
  }

  private String responseFrom(final CompletionStage<String> future) {
    final TestProbe<Msg> probe = testKit.createTestProbe();
    final Behavior<Msg> behavior =
        Behaviors.setup(
            context -> {
              context.pipeToSelf(
                  future,
                  (string, exception) -> {
                    final String response;
                    if (string != null) response = String.format("ok: %s", string);
                    else if (exception != null)
                      response = String.format("ko: %s", exception.getMessage());
                    else response = "???";
                    return new Msg(
                        response,
                        context.getSelf().path().name(),
                        Thread.currentThread().getName());
                  });
              return Behaviors.receiveMessage(
                  msg -> {
                    probe.getRef().tell(msg);
                    return Behaviors.stopped();
                  });
            });
    final String name = "pipe-to-self-spec";
    final Props props = Props.empty().withDispatcherFromConfig("pipe-to-self-spec-dispatcher");

    testKit.spawn(behavior, name, props);

    final Msg msg = probe.expectMessageClass(Msg.class);

    assertEquals("pipe-to-self-spec", msg.selfName);
    assertThat(
        msg.threadName, startsWith("ActorContextPipeToSelfTest-pipe-to-self-spec-dispatcher"));
    return msg.response;
  }
}
