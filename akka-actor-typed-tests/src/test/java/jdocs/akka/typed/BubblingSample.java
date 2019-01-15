/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.typed;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;

// #bubbling-example
public class BubblingSample {
  interface Message {}

  public static class Fail implements Message {
    public final String text;

    public Fail(String text) {
      this.text = text;
    }
  }

  public static Behavior<Message> failingChildBehavior =
      Behaviors.receive(Message.class)
          .onMessage(
              Fail.class,
              (context, message) -> {
                throw new RuntimeException(message.text);
              })
          .build();

  public static Behavior<Message> middleManagementBehavior =
      Behaviors.setup(
          (context) -> {
            context.getLog().info("Middle management starting up");
            final ActorRef<Message> child = context.spawn(failingChildBehavior, "child");
            // we want to know when the child terminates, but since we do not handle
            // the Terminated signal, we will in turn fail on child termination
            context.watch(child);

            // here we don't handle Terminated at all which means that
            // when the child fails or stops gracefully this actor will
            // fail with a DeathWatchException
            return Behaviors.receive(Message.class)
                .onMessage(
                    Message.class,
                    (innerCtx, message) -> {
                      // just pass messages on to the child
                      child.tell(message);
                      return Behaviors.same();
                    })
                .build();
          });

  public static Behavior<Message> bossBehavior =
      Behaviors.setup(
          (context) -> {
            context.getLog().info("Boss starting up");
            final ActorRef<Message> middleManagement =
                context.spawn(middleManagementBehavior, "middle-management");
            context.watch(middleManagement);

            // here we don't handle Terminated at all which means that
            // when middle management fails with a DeathWatchException
            // this actor will also fail
            return Behaviors.receive(Message.class)
                .onMessage(
                    Message.class,
                    (innerCtx, message) -> {
                      // just pass messages on to the child
                      middleManagement.tell(message);
                      return Behaviors.same();
                    })
                .build();
          });

  public static void main(String[] args) {
    final ActorSystem<Message> system = ActorSystem.create(bossBehavior, "boss");

    system.tell(new Fail("boom"));
    // this will now bubble up all the way to the boss and as that is the user guardian it means
    // the entire actor system will stop
  }
}
// #bubbling-example
