/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.cluster.sharding.typed;

import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;

public class ShardingReplyCompileOnlyTest {

  ActorSystem system = ActorSystem.create(Behaviors.empty(), "ShardingExample");
  ClusterSharding sharding = ClusterSharding.get(system);

  // #sharded-response
  // a sharded actor that needs counter updates
  EntityTypeKey<Command> typeKey = EntityTypeKey.create(Command.class, "example-sharded-response");

  public interface Command {}

  class NewCount implements Command {
    public final long value;

    NewCount(long value) {
      this.value = value;
    }
  }

  // a sharded counter that sends responses to another sharded actor
  interface CounterCommand {}

  enum Increment implements CounterCommand {
    INSTANCE
  }

  class GetValue implements CounterCommand {
    public final String replyToEntityId;

    GetValue(String replyToEntityId) {
      this.replyToEntityId = replyToEntityId;
    }
  }

  public Behavior<CounterCommand> counter(int value) {
    return Behaviors.receive(CounterCommand.class)
        .onMessage(Increment.class, msg -> counter(value + 1))
        .onMessage(
            GetValue.class,
            msg -> {
              sharding.entityRefFor(typeKey, msg.replyToEntityId).tell(new NewCount(value));
              return Behaviors.same();
            })
        .build();
  }
  // #sharded-response
}
