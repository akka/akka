/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.typed.supervision;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.Behaviors;
import scala.concurrent.duration.FiniteDuration;

import java.util.concurrent.TimeUnit;

public class SupervisionCompileOnlyTest {
  // #wrap
  public static class Counter {
    public interface Command {}

    public static final class Increase implements Command {}

    public static final class Get implements Command {
      public final ActorRef<Got> replyTo;

      public Get(ActorRef<Got> replyTo) {
        this.replyTo = replyTo;
      }
    }

    public static final class Got {
      public final int n;

      public Got(int n) {
        this.n = n;
      }
    }

    // #top-level
    public static Behavior<Command> create() {
      return Behaviors.supervise(counter(1)).onFailure(SupervisorStrategy.restart());
    }
    // #top-level

    private static Behavior<Command> counter(int currentValue) {
      return Behaviors.receive(Command.class)
          .onMessage(Increase.class, o -> onIncrease(currentValue))
          .onMessage(Get.class, command -> onGet(currentValue, command))
          .build();
    }

    private static Behavior<Command> onIncrease(int currentValue) {
      return counter(currentValue + 1);
    }

    private static Behavior<Command> onGet(int currentValue, Get command) {
      command.replyTo.tell(new Got(currentValue));
      return Behaviors.same();
    }
  }
  // #wrap

  public static Behavior<String> behavior = Behaviors.empty();

  public void supervision() {
    // #restart
    Behaviors.supervise(behavior)
        .onFailure(IllegalStateException.class, SupervisorStrategy.restart());
    // #restart

    // #resume
    Behaviors.supervise(behavior)
        .onFailure(IllegalStateException.class, SupervisorStrategy.resume());
    // #resume

    // #restart-limit
    Behaviors.supervise(behavior)
        .onFailure(
            IllegalStateException.class,
            SupervisorStrategy.restart().withLimit(10, FiniteDuration.apply(10, TimeUnit.SECONDS)));
    // #restart-limit

    // #multiple
    Behaviors.supervise(
            Behaviors.supervise(behavior)
                .onFailure(IllegalStateException.class, SupervisorStrategy.restart()))
        .onFailure(IllegalArgumentException.class, SupervisorStrategy.stop());
    // #multiple

  }

  // #restart-stop-children
  static Behavior<String> child(long size) {
    return Behaviors.receiveMessage(msg -> child(size + msg.length()));
  }

  static Behavior<String> parent() {
    return Behaviors.<String>supervise(
            Behaviors.setup(
                ctx -> {
                  final ActorRef<String> child1 = ctx.spawn(child(0), "child1");
                  final ActorRef<String> child2 = ctx.spawn(child(0), "child2");

                  return Behaviors.receiveMessage(
                      msg -> {
                        // there might be bugs here...
                        String[] parts = msg.split(" ");
                        child1.tell(parts[0]);
                        child2.tell(parts[1]);
                        return Behaviors.same();
                      });
                }))
        .onFailure(SupervisorStrategy.restart());
  }
  // #restart-stop-children

  // #restart-keep-children
  static Behavior<String> parent2() {
    return Behaviors.setup(
        ctx -> {
          final ActorRef<String> child1 = ctx.spawn(child(0), "child1");
          final ActorRef<String> child2 = ctx.spawn(child(0), "child2");

          // supervision strategy inside the setup to not recreate children on restart
          return Behaviors.<String>supervise(
                  Behaviors.receiveMessage(
                      msg -> {
                        // there might be bugs here...
                        String[] parts = msg.split(" ");
                        child1.tell(parts[0]);
                        child2.tell(parts[1]);
                        return Behaviors.same();
                      }))
              .onFailure(SupervisorStrategy.restart().withStopChildren(false));
        });
  }
  // #restart-keep-children

}
