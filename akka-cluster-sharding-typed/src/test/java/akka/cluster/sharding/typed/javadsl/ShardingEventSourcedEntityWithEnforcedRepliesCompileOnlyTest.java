/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.javadsl;

import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.typed.ActorRef;
import akka.cluster.typed.Cluster;
import akka.cluster.typed.Join;
import akka.persistence.typed.ExpectingReply;
import akka.persistence.typed.javadsl.*;
import org.junit.ClassRule;

public class ShardingEventSourcedEntityWithEnforcedRepliesCompileOnlyTest {

  @ClassRule public static final TestKitJunitResource testKit = new TestKitJunitResource();

  interface Command extends ExpectingReply<String> {}

  static class Append implements Command {
    public final String s;
    private final ActorRef<String> replyToRef;

    Append(String s, ActorRef<String> replyTo) {
      this.s = s;
      this.replyToRef = replyTo;
    }

    @Override
    public ActorRef<String> replyTo() {
      return replyToRef;
    }
  }

  static class TestPersistentEntityWithEnforcedReplies
      extends EventSourcedEntityWithEnforcedReplies<Command, String, String> {

    public static final EntityTypeKey<Command> ENTITY_TYPE_KEY =
        EntityTypeKey.create(Command.class, "HelloWorld");

    public TestPersistentEntityWithEnforcedReplies(String entityId) {
      super(ENTITY_TYPE_KEY, entityId);
    }

    @Override
    public String emptyState() {
      return "";
    }

    @Override
    public CommandHandlerWithReply<Command, String, String> commandHandler() {
      return newCommandHandlerWithReplyBuilder()
          .forAnyState()
          .onCommand(Append.class, this::add)
          .build();
    }

    private ReplyEffect<String, String> add(String state, Append cmd) {
      return Effect().persist(cmd.s).thenReply(cmd, s -> "Ok");
    }

    @Override
    public EventHandler<String, String> eventHandler() {
      return newEventHandlerBuilder().forAnyState().onEvent(String.class, this::applyEvent).build();
    }

    private String applyEvent(String state, String evt) {
      if (state.trim().isEmpty()) return evt;
      else return state + "|" + evt;
    }
  }

  private void shardingForEventSourcedEntityWithReplies() {

    // initialize first time only
    Cluster cluster = Cluster.get(testKit.system());
    cluster.manager().tell(new Join(cluster.selfMember().address()));

    ClusterSharding sharding = ClusterSharding.get(testKit.system());

    sharding.init(
        Entity.ofEventSourcedEntityWithEnforcedReplies(
            TestPersistentEntityWithEnforcedReplies.ENTITY_TYPE_KEY,
            entityContext ->
                new TestPersistentEntityWithEnforcedReplies(entityContext.getEntityId())));
  }
}
