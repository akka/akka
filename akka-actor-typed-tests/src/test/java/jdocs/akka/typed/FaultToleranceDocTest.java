/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.typed;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

public class FaultToleranceDocTest extends JUnitSuite {
  // #bubbling-example
  interface Message {}
  class Fail implements Message {
    public final String text;
    Fail(String text) {
      this.text = text;
    }
  }

  // #bubbling-example

  @Test
  public void bubblingSample() {
    // #bubbling-example
    final Behavior<Message> failingChildBehavior = Behaviors.receive(Message.class)
      .onMessage(Fail.class, (ctx, message) -> {
        throw new RuntimeException(message.text);
      })
      .build();

    Behavior<Message> middleManagementBehavior = Behaviors.setup((ctx) -> {
      ctx.getLog().info("Middle management starting up");
      final ActorRef<Message> child = ctx.spawn(failingChildBehavior, "child");
      // we want to know when the child terminates, but since we do not handle
      // the Terminated signal, we will in turn fail on child termination
      ctx.watch(child);

      // here we don't handle Terminated at all which means that
      // when the child fails or stops gracefully this actor will
      // fail with a DeathWatchException
      return Behaviors.receive(Message.class)
        .onMessage(Message.class, (innerCtx, msg) -> {
          // just pass messages on to the child
          child.tell(msg);
          return Behaviors.same();
        }).build();
    });

    Behavior<Message> bossBehavior = Behaviors.setup((ctx) -> {
      ctx.getLog().info("Boss starting up");
      final ActorRef<Message> middleManagement = ctx.spawn(middleManagementBehavior, "middle-management");
      ctx.watch(middleManagement);

      // here we don't handle Terminated at all which means that
      // when middle management fails with a DeathWatchException
      // this actor will also fail
      return Behaviors.receive(Message.class)
        .onMessage(Message.class, (innerCtx, msg) -> {
          // just pass messages on to the child
          middleManagement.tell(msg);
          return Behaviors.same();
        }).build();
    });

    final ActorSystem<Message> system =
      ActorSystem.create(bossBehavior, "boss");

    system.tell(new Fail("boom"));
    // this will now bubble up all the way to the boss and as that is the user guardian it means
    // the entire actor system will stop

    // #bubbling-example

  }

}
