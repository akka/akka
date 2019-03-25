/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.cluster.sharding.typed;

import java.time.Duration;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;

// #import
import akka.cluster.sharding.typed.ShardingEnvelope;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;
import akka.cluster.sharding.typed.javadsl.EntityRef;
import akka.cluster.sharding.typed.javadsl.Entity;

// #import

import jdocs.akka.persistence.typed.BlogPostExample.BlogCommand;
import jdocs.akka.persistence.typed.BlogPostExample.BlogBehavior;

public class ShardingCompileOnlyTest {

  // #counter-messages
  interface CounterCommand {}

  public static class Increment implements CounterCommand {}

  public static class GetValue implements CounterCommand {
    private final ActorRef<Integer> replyTo;

    public GetValue(ActorRef<Integer> replyTo) {
      this.replyTo = replyTo;
    }
  }
  // #counter-messages

  // #counter

  public static Behavior<CounterCommand> counter(String entityId, Integer value) {
    return Behaviors.receive(CounterCommand.class)
        .onMessage(
            Increment.class,
            (ctx, msg) -> {
              return counter(entityId, value + 1);
            })
        .onMessage(
            GetValue.class,
            (ctx, msg) -> {
              msg.replyTo.tell(value);
              return Behaviors.same();
            })
        .build();
  }
  // #counter

  // #counter-passivate
  public static class Idle implements CounterCommand {}

  public static class GoodByeCounter implements CounterCommand {}

  public static Behavior<CounterCommand> counter2(
      ActorRef<ClusterSharding.ShardCommand> shard, String entityId) {
    return Behaviors.setup(
        ctx -> {
          ctx.setReceiveTimeout(Duration.ofSeconds(30), new Idle());
          return counter2(shard, entityId, 0);
        });
  }

  private static Behavior<CounterCommand> counter2(
      ActorRef<ClusterSharding.ShardCommand> shard, String entityId, Integer value) {
    return Behaviors.receive(CounterCommand.class)
        .onMessage(
            Increment.class,
            (ctx, msg) -> {
              return counter(entityId, value + 1);
            })
        .onMessage(
            GetValue.class,
            (ctx, msg) -> {
              msg.replyTo.tell(value);
              return Behaviors.same();
            })
        .onMessage(
            Idle.class,
            (ctx, msg) -> {
              // after receive timeout
              shard.tell(new ClusterSharding.Passivate<>(ctx.getSelf()));
              return Behaviors.same();
            })
        .onMessage(
            GoodByeCounter.class,
            (ctx, msg) -> {
              // the stopMessage, used for rebalance and passivate
              return Behaviors.stopped();
            })
        .build();
  }
  // #counter-passivate

  public static void initPassivateExample() {
    ActorSystem system = ActorSystem.create(Behaviors.empty(), "ShardingExample");
    ClusterSharding sharding = ClusterSharding.get(system);

    // #counter-passivate-init

    EntityTypeKey<CounterCommand> typeKey = EntityTypeKey.create(CounterCommand.class, "Counter");

    sharding.init(
        Entity.of(typeKey, ctx -> counter2(ctx.getShard(), ctx.getEntityId()))
            .withStopMessage(new GoodByeCounter()));
    // #counter-passivate-init
  }

  public static void example() {

    ActorSystem system = ActorSystem.create(Behaviors.empty(), "ShardingExample");

    // #sharding-extension
    ClusterSharding sharding = ClusterSharding.get(system);
    // #sharding-extension

    // #init
    EntityTypeKey<CounterCommand> typeKey = EntityTypeKey.create(CounterCommand.class, "Counter");

    ActorRef<ShardingEnvelope<CounterCommand>> shardRegion =
        sharding.init(Entity.of(typeKey, ctx -> counter(ctx.getEntityId(), 0)));
    // #init

    // #send
    EntityRef<CounterCommand> counterOne = sharding.entityRefFor(typeKey, "counter-`");
    counterOne.tell(new Increment());

    shardRegion.tell(new ShardingEnvelope<>("counter-1", new Increment()));
    // #send
  }

  public static void persistenceExample() {
    ActorSystem system = ActorSystem.create(Behaviors.empty(), "ShardingExample");
    ClusterSharding sharding = ClusterSharding.get(system);

    // #persistence
    EntityTypeKey<BlogCommand> blogTypeKey = EntityTypeKey.create(BlogCommand.class, "BlogPost");

    sharding.init(Entity.of(blogTypeKey, ctx -> BlogBehavior.behavior(ctx.getEntityId())));
    // #persistence
  }
}
