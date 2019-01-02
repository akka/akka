/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.javadsl;

import akka.Done;
import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import akka.cluster.typed.Cluster;
import akka.cluster.typed.Join;
import akka.persistence.typed.ExpectingReply;
import akka.persistence.typed.javadsl.CommandHandler;
import akka.persistence.typed.javadsl.Effect;
import akka.persistence.typed.javadsl.EventHandler;
import akka.util.Timeout;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import java.util.concurrent.CompletionStage;

public class ClusterShardingPersistenceTest extends JUnitSuite {

  public static final Config config = ConfigFactory.parseString(
          "akka.actor.provider = cluster \n" +
          "akka.remote.netty.tcp.port = 0 \n" +
          "akka.remote.artery.canonical.port = 0 \n" +
          "akka.remote.artery.canonical.hostname = 127.0.0.1 \n" +
          "akka.persistence.journal.plugin = \"akka.persistence.journal.inmem\" \n");

  @ClassRule
  public static final TestKitJunitResource testKit = new TestKitJunitResource(config);

  interface Command {}
  static class Add implements Command {
    public final String s;

    Add(String s) {
      this.s = s;
    }
  }
  static class AddWithConfirmation implements Command, ExpectingReply<Done> {
    final String s;
    private final ActorRef<Done> replyTo;

    AddWithConfirmation(String s, ActorRef<Done> replyTo) {
      this.s = s;
      this.replyTo = replyTo;
    }

    @Override
    public ActorRef<Done> replyTo() {
      return replyTo;
    }
  }
  static class Get implements Command {
    final ActorRef<String> replyTo;

    Get(ActorRef<String> replyTo) {
      this.replyTo = replyTo;
    }
  }
  static enum StopPlz implements Command {
    INSTANCE
  }



  static class TestPersistentEntity extends EventSourcedEntity<Command, String, String> {

    public static final EntityTypeKey<Command> ENTITY_TYPE_KEY =
      EntityTypeKey.create(Command.class, "HelloWorld");

    public TestPersistentEntity(String entityId) {
      super(ENTITY_TYPE_KEY, entityId);
    }

    @Override
    public String emptyState() {
      return "";
    }

    @Override
    public CommandHandler<Command, String, String> commandHandler() {
      return commandHandlerBuilder(String.class)
        .matchCommand(Add.class, this::add)
        .matchCommand(AddWithConfirmation.class, this::addWithConfirmation)
        .matchCommand(Get.class, this::getState)
        .build();
    }

    private Effect<String, String> add(String state, Add cmd) {
      return Effect().persist(cmd.s);
    }

    private Effect<String, String> addWithConfirmation(String state, AddWithConfirmation cmd) {
      return Effect().persist(cmd.s)
        .thenReply(cmd, newState -> Done.getInstance());
    }

    private Effect<String, String> getState(String state, Get cmd) {
      cmd.replyTo.tell(entityId() + ":" + state);
      return Effect().none();
    }

    @Override
    public EventHandler<String, String> eventHandler() {
      return eventHandlerBuilder()
        .matchEvent(String.class, this::applyEvent)
        .build();
    }

    private String applyEvent(String state, String evt) {
      if (state.equals(""))
        return evt;
      else
        return state + "|" + evt;
    }
  }

  private ClusterSharding _sharding = null;

  private ClusterSharding sharding() {
    if (_sharding == null) {
      // initialize first time only
      Cluster cluster = Cluster.get(testKit.system());
      cluster.manager().tell(new Join(cluster.selfMember().address()));

      ClusterSharding sharding = ClusterSharding.get(testKit.system());

      sharding.init(Entity.ofPersistentEntity(TestPersistentEntity.ENTITY_TYPE_KEY,
        entityContext -> new TestPersistentEntity(entityContext.getEntityId()))
      .withStopMessage(StopPlz.INSTANCE));

      _sharding = sharding;
    }
    return _sharding;
  }

  @Test
  public void startPersistentActor() {
    TestProbe<String> p = testKit.createTestProbe();
    EntityRef<Command> ref = sharding().entityRefFor(TestPersistentEntity.ENTITY_TYPE_KEY, "123");
    ref.tell(new Add("a"));
    ref.tell(new Add("b"));
    ref.tell(new Add("c"));
    ref.tell(new Get(p.getRef()));
    p.expectMessage("123:a|b|c");
  }

  @Test
  public void askWithThenReply() {
    TestProbe<Done> p1 = testKit.createTestProbe();
    EntityRef<Command> ref = sharding().entityRefFor(TestPersistentEntity.ENTITY_TYPE_KEY, "456");
    Timeout askTimeout = Timeout.create(p1.getRemainingOrDefault());
    CompletionStage<Done> done1 =ref.ask(replyTo -> new AddWithConfirmation("a", replyTo), askTimeout);
    done1.thenAccept(d -> p1.getRef().tell(d));
    p1.expectMessage(Done.getInstance());

    CompletionStage<Done> done2 =ref.ask(replyTo -> new AddWithConfirmation("b", replyTo), askTimeout);
    done1.thenAccept(d -> p1.getRef().tell(d));
    p1.expectMessage(Done.getInstance());

    TestProbe<String> p2 = testKit.createTestProbe();
    ref.tell(new Get(p2.getRef()));
    p2.expectMessage("456:a|b");
  }

}
