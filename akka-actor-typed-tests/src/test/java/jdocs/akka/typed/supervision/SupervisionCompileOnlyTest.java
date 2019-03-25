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
  interface CounterMessage {}

  public static final class Increase implements CounterMessage {}

  public static final class Get implements CounterMessage {
    final ActorRef<Got> sender;

    public Get(ActorRef<Got> sender) {
      this.sender = sender;
    }
  }

  public static final class Got {
    final int n;

    public Got(int n) {
      this.n = n;
    }
  }

  public static Behavior<CounterMessage> counter(int currentValue) {
    return Behaviors.receive(CounterMessage.class)
        .onMessage(
            Increase.class,
            (context, o) -> {
              return counter(currentValue + 1);
            })
        .onMessage(
            Get.class,
            (context, o) -> {
              o.sender.tell(new Got(currentValue));
              return Behaviors.same();
            })
        .build();
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

    // #top-level
    Behaviors.supervise(counter(1));
    // #top-level

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
