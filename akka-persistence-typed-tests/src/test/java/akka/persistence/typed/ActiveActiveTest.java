/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed;

import akka.Done;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.persistence.testkit.query.javadsl.PersistenceTestKitReadJournal;
import akka.persistence.typed.javadsl.*;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static akka.Done.done;

public class ActiveActiveTest {

  static final class TestBehavior
      extends ActiveActiveEventSourcedBehavior<TestBehavior.Command, String, Set<String>> {
    interface Command {}

    static final class GetState implements Command {
      final ActorRef<Set<String>> replyTo;

      public GetState(ActorRef<Set<String>> replyTo) {
        this.replyTo = replyTo;
      }
    }

    static final class StoreMe implements Command {
      final String text;
      final ActorRef<Done> replyTo;

      public StoreMe(String text, ActorRef<Done> replyTo) {
        this.text = text;
        this.replyTo = replyTo;
      }
    }

    static final class StoreUs implements Command {
      final List<String> texts;
      final ActorRef<Done> replyTo;

      public StoreUs(List<String> texts, ActorRef<Done> replyTo) {
        this.texts = texts;
        this.replyTo = replyTo;
      }
    }

    static final class GetReplica implements Command {
      final ActorRef<ReplicaId> replyTo;

      public GetReplica(ActorRef<ReplicaId> replyTo) {
        this.replyTo = replyTo;
      }
    }

    enum Stop implements Command {
      INSTANCE
    }

    public static Behavior<Command> create(
        String entityId, ReplicaId replicaId, Set<ReplicaId> allReplicas) {
      return ActiveActiveEventSourcing.withSharedJournal(
          entityId,
          replicaId,
          allReplicas,
          PersistenceTestKitReadJournal.Identifier(),
          TestBehavior::new);
    }

    private TestBehavior(ActiveActiveContext activeActiveContext) {
      super(activeActiveContext);
    }

    @Override
    public Set<String> emptyState() {
      return Collections.emptySet();
    }

    @Override
    public CommandHandler<Command, String, Set<String>> commandHandler() {
      return newCommandHandlerBuilder()
          .forAnyState()
          .onCommand(
              StoreMe.class,
              (StoreMe cmd) -> Effect().persist(cmd.text).thenRun(__ -> cmd.replyTo.tell(done())))
          .onCommand(
              StoreUs.class,
              (StoreUs cmd) -> Effect().persist(cmd.texts).thenRun(__ -> cmd.replyTo.tell(done())))
          .onCommand(
              GetState.class,
              (GetState get) ->
                  Effect().none().thenRun(state -> get.replyTo.tell(new HashSet<>(state))))
          .onCommand(
              GetReplica.class,
              (GetReplica cmd) ->
                  Effect()
                      .none()
                      .thenRun(() -> cmd.replyTo.tell(getActiveActiveContext().replicaId())))
          .onCommand(Stop.class, __ -> Effect().stop())
          .build();
    }

    @Override
    public EventHandler<Set<String>, String> eventHandler() {
      return newEventHandlerBuilder()
          .forAnyState()
          .onAnyEvent(
              (state, text) -> {
                // FIXME mutable - state I don't remember if we support or not so defensive copy for
                // now
                Set<String> newSet = new HashSet<>(state);
                newSet.add(text);
                return newSet;
              });
    }
  }

  // FIXME API compile only for now

}
