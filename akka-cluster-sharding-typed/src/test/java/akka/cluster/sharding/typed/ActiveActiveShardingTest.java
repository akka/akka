/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed;

import akka.actor.testkit.typed.javadsl.LogCapturing;
import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.cluster.MemberStatus;
import akka.cluster.sharding.typed.javadsl.Entity;
import akka.cluster.sharding.typed.scaladsl.EntityRef;
import akka.cluster.typed.Cluster;
import akka.cluster.typed.Join;
import akka.persistence.testkit.PersistenceTestKitPlugin;
import akka.persistence.testkit.query.javadsl.PersistenceTestKitReadJournal;
import akka.persistence.typed.ReplicaId;
import akka.persistence.typed.javadsl.*;
import com.typesafe.config.ConfigFactory;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;
import scala.util.Random;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

public class ActiveActiveShardingTest extends JUnitSuite {

  static class MyActiveActiveStringSet
      extends ActiveActiveEventSourcedBehavior<
          MyActiveActiveStringSet.Command, String, Set<String>> {
    interface Command {}

    static class Add implements Command {
      public final String text;

      public Add(String text) {
        this.text = text;
      }
    }

    static class GetTexts implements Command {
      public final ActorRef<Texts> replyTo;

      public GetTexts(ActorRef<Texts> replyTo) {
        this.replyTo = replyTo;
      }
    }

    static class Texts {
      public final Set<String> texts;

      public Texts(Set<String> texts) {
        this.texts = texts;
      }
    }

    static Behavior<Command> create(
        String entityId, ReplicaId replicaId, Set<ReplicaId> allReplicas) {
      return ActiveActiveEventSourcing.withSharedJournal(
          entityId,
          replicaId,
          allReplicas,
          PersistenceTestKitReadJournal.Identifier(),
          MyActiveActiveStringSet::new);
    }

    private MyActiveActiveStringSet(ActiveActiveContext activeActiveContext) {
      super(activeActiveContext);
    }

    @Override
    public Set<String> emptyState() {
      return new HashSet<>();
    }

    @Override
    public CommandHandler<Command, String, Set<String>> commandHandler() {
      return newCommandHandlerBuilder()
          .forAnyState()
          .onCommand(Add.class, add -> Effect().persist(add.text))
          .onCommand(
              GetTexts.class,
              (state, get) -> {
                // protective copy
                get.replyTo.tell(new Texts(new HashSet<>(state)));
                return Effect().none();
              })
          .build();
    }

    @Override
    public EventHandler<Set<String>, String> eventHandler() {
      return newEventHandlerBuilder()
          .forAnyState()
          .onAnyEvent(
              (state, text) -> {
                state.add(text);
                return state;
              });
    }
  }

  public static class ProxyActor extends AbstractBehavior<ProxyActor.Command> {
    interface Command {}

    public static final class ForwardToRandom implements Command {
      public final String entityId;
      public final MyActiveActiveStringSet.Command message;

      public ForwardToRandom(String entityId, MyActiveActiveStringSet.Command message) {
        this.entityId = entityId;
        this.message = message;
      }
    }

    public static final class ForwardToAll implements Command {
      public final String entityId;
      public final MyActiveActiveStringSet.Command message;

      public ForwardToAll(String entityId, MyActiveActiveStringSet.Command message) {
        this.entityId = entityId;
        this.message = message;
      }
    }

    public static Behavior<Command> create() {
      return Behaviors.setup(ProxyActor::new);
    }

    public static final Set<ReplicaId> ALL_REPLICAS =
        Collections.unmodifiableSet(
            new HashSet<>(
                Arrays.asList(
                    new ReplicaId("DC-A"), new ReplicaId("DC-B"), new ReplicaId("DC-C"))));

    private final ActiveActiveSharding<
            MyActiveActiveStringSet.Command, ShardingEnvelope<MyActiveActiveStringSet.Command>>
        aaSharding;

    private ProxyActor(ActorContext<Command> context) {
      super(context);

      // #bootstrap
      ActiveActiveShardingSettings<
              MyActiveActiveStringSet.Command, ShardingEnvelope<MyActiveActiveStringSet.Command>>
          aaShardingSettings =
              ActiveActiveShardingSettings.create(
                  MyActiveActiveStringSet.Command.class,
                  ALL_REPLICAS,
                  // factory for replica settings for a given replica
                  (entityTypeKey, replicaId, allReplicas) ->
                      ReplicaSettings.create(
                          replicaId,
                          // use the replica id as typekey for sharding to get one sharding instance
                          // per replica
                          Entity.of(
                                  entityTypeKey,
                                  entityContext ->
                                      // factory for the entity for a given entity in that replica
                                      MyActiveActiveStringSet.create(
                                          entityContext.getEntityId(), replicaId, allReplicas))
                              // potentially use replica id as role or dc in Akka multi dc for the
                              // sharding instance
                              // to control where replicas will live
                              // .withDataCenter(replicaId.id()))
                              .withRole(replicaId.id())));

      ActiveActiveShardingExtension extension =
          ActiveActiveShardingExtension.get(getContext().getSystem());
      aaSharding = extension.init(aaShardingSettings);
      // #bootstrap
    }

    @Override
    public Receive<Command> createReceive() {
      return newReceiveBuilder()
          .onMessage(ForwardToRandom.class, this::onForwardToRandom)
          .onMessage(ForwardToAll.class, this::onForwardToAll)
          .build();
    }

    private Behavior<Command> onForwardToRandom(ForwardToRandom forwardToRandom) {
      Map<ReplicaId, EntityRef<MyActiveActiveStringSet.Command>> refs =
          aaSharding.getEntityRefsFor(forwardToRandom.entityId);
      int chosenIdx = new java.util.Random().nextInt(refs.size());
      new ArrayList<>(refs.values()).get(chosenIdx).tell(forwardToRandom.message);
      return this;
    }

    private Behavior<Command> onForwardToAll(ForwardToAll forwardToAll) {
      // #all-entity-refs
      Map<ReplicaId, EntityRef<MyActiveActiveStringSet.Command>> refs =
          aaSharding.getEntityRefsFor(forwardToAll.entityId);
      refs.forEach((replicaId, ref) -> ref.tell(forwardToAll.message));
      // #all-entity-refs
      return this;
    }
  }

  @ClassRule
  public static final TestKitJunitResource testKit =
      new TestKitJunitResource(
          ConfigFactory.parseString(
                  " akka.loglevel = DEBUG\n"
                      + "      akka.loggers = [\"akka.testkit.SilenceAllTestEventListener\"]\n"
                      + "      akka.actor.provider = \"cluster\"\n"
                      + "      # pretend we're a node in all dc:s\n"
                      + "      akka.cluster.roles = [\"DC-A\", \"DC-B\", \"DC-C\"]\n"
                      + "      akka.remote.classic.netty.tcp.port = 0\n"
                      + "      akka.remote.artery.canonical.port = 0")
              .withFallback(PersistenceTestKitPlugin.getInstance().config()));

  @Rule public final LogCapturing logCapturing = new LogCapturing();

  @Test
  public void formClusterAndInteractWithReplicas() {
    // join ourselves to form a one node cluster
    Cluster node = Cluster.get(testKit.system());
    node.manager().tell(new Join(node.selfMember().address()));
    TestProbe<Object> testProbe = testKit.createTestProbe();
    testProbe.awaitAssert(
        () -> {
          assertEquals(MemberStatus.up(), node.selfMember().status());
          return null;
        });

    // forward messages to replicas
    ActorRef<ProxyActor.Command> proxy = testKit.spawn(ProxyActor.create());

    proxy.tell(new ProxyActor.ForwardToAll("id1", new MyActiveActiveStringSet.Add("to-all")));
    proxy.tell(new ProxyActor.ForwardToRandom("id1", new MyActiveActiveStringSet.Add("to-random")));

    testProbe.awaitAssert(
        () -> {
          TestProbe<MyActiveActiveStringSet.Texts> responseProbe = testKit.createTestProbe();
          proxy.tell(
              new ProxyActor.ForwardToAll(
                  "id1", new MyActiveActiveStringSet.GetTexts(responseProbe.ref())));
          List<MyActiveActiveStringSet.Texts> responses = responseProbe.receiveSeveralMessages(3);
          Set<String> uniqueTexts =
              responses.stream().flatMap(res -> res.texts.stream()).collect(Collectors.toSet());
          assertEquals(new HashSet<>(Arrays.asList("to-all", "to-random")), uniqueTexts);
          return null;
        });
  }
}
