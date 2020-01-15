/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.persistence;

import akka.Done;
import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.AskPattern;
import akka.persistence.query.PersistenceQuery;
import akka.persistence.query.Sequence;
import akka.stream.javadsl.Sink;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static jdocs.persistence.PersistenceQueryDocTest.*;

public interface ResumableProjectionExample {

  public static void runQuery(
      ActorSystem<?> system, ActorRef<TheOneWhoWritesToQueryJournal.Command> writer)
      throws Exception {

    final MyJavadslReadJournal readJournal =
        PersistenceQuery.get(system)
            .getReadJournalFor(
                MyJavadslReadJournal.class, "akka.persistence.query.my-read-journal");

    // #projection-into-different-store-actor-run
    final Duration timeout = Duration.ofSeconds(3);

    final MyResumableProjection bidProjection = new MyResumableProjection("bid");

    long startFromOffset =
        bidProjection.latestOffset().toCompletableFuture().get(3, TimeUnit.SECONDS);

    readJournal
        .eventsByTag("bid", new Sequence(startFromOffset))
        .mapAsync(
            8,
            envelope -> {
              final CompletionStage<Done> f =
                  AskPattern.ask(
                      writer,
                      (ActorRef<Done> replyTo) ->
                          new TheOneWhoWritesToQueryJournal.Update(envelope.event(), replyTo),
                      timeout,
                      system.scheduler());
              return f.thenApplyAsync(in -> envelope.offset(), system.executionContext());
            })
        .mapAsync(1, offset -> bidProjection.saveProgress(offset))
        .runWith(Sink.ignore(), system);
  }

  // #projection-into-different-store-actor-run

  // #projection-into-different-store-actor
  static final class TheOneWhoWritesToQueryJournal
      extends AbstractBehavior<TheOneWhoWritesToQueryJournal.Command> {

    interface Command {}

    static class Update implements Command {
      public final Object payload;
      public final ActorRef<Done> replyTo;

      Update(Object payload, ActorRef<Done> replyTo) {
        this.payload = payload;
        this.replyTo = replyTo;
      }
    }

    public static Behavior<Command> create(String id, ExampleStore store) {
      return Behaviors.setup(context -> new TheOneWhoWritesToQueryJournal(context, store));
    }

    private final ExampleStore store;

    private ComplexState state = new ComplexState();

    private TheOneWhoWritesToQueryJournal(ActorContext<Command> context, ExampleStore store) {
      super(context);
      this.store = store;
    }

    @Override
    public Receive<Command> createReceive() {
      return newReceiveBuilder().onMessage(Update.class, this::onUpdate).build();
    }

    private Behavior<Command> onUpdate(Update msg) {
      state = updateState(state, msg);
      if (state.readyToSave()) store.save(Record.of(state));
      return this;
    }

    ComplexState updateState(ComplexState state, Update msg) {
      // some complicated aggregation logic here ...
      return state;
    }
  }
  // #projection-into-different-store-actor

}
