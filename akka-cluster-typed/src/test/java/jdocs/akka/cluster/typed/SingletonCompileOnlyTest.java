/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.cluster.typed;

import akka.actor.typed.*;
import akka.actor.typed.javadsl.Behaviors;

//#import
import akka.cluster.typed.ClusterSingleton;
import akka.cluster.typed.ClusterSingletonSettings;
import akka.cluster.typed.Singleton;

import java.time.Duration;

//#import

public class SingletonCompileOnlyTest {

  //#counter
  interface CounterCommand {}
  public static class Increment implements CounterCommand { }
  public static class GoodByeCounter implements CounterCommand { }

  public static class GetValue implements CounterCommand {
    private final ActorRef<Integer> replyTo;
    public GetValue(ActorRef<Integer> replyTo) {
      this.replyTo = replyTo;
    }
  }

  public static Behavior<CounterCommand> counter(String entityId, Integer value) {
    return Behaviors.receive(CounterCommand.class)
      .onMessage(Increment.class, (ctx, msg) -> counter(entityId,value + 1))
      .onMessage(GetValue.class, (ctx, msg) -> {
        msg.replyTo.tell(value);
        return Behaviors.same();
      })
      .onMessage(GoodByeCounter.class, (ctx, msg) -> Behaviors.stopped())
      .build();
  }
  //#counter

  public static void example() {

    ActorSystem system = ActorSystem.create(
            Behaviors.empty(), "SingletonExample"
    );

    //#singleton
    ClusterSingleton singleton = ClusterSingleton.get(system);
    // Start if needed and provide a proxy to a named singleton
    ActorRef<CounterCommand> proxy = singleton.init(
            Singleton.of("GlobalCounter",counter("TheCounter", 0)).withStopMessage(new GoodByeCounter())
    );

    proxy.tell(new Increment());
    //#singleton

  }

  public static void backoff() {

    ActorSystem system = ActorSystem.create(
      Behaviors.empty(), "SingletonExample"
    );

    //#backoff
    ClusterSingleton singleton = ClusterSingleton.get(system);
    ActorRef<CounterCommand> proxy = singleton.init(
            Singleton.of("GlobalCounter",
      Behaviors.supervise(counter("TheCounter", 0))
              .onFailure(SupervisorStrategy.restartWithBackoff(Duration.ofSeconds(1), Duration.ofSeconds(10), 0.2))).withStopMessage(new GoodByeCounter())
    );
    //#backoff
  }
}
