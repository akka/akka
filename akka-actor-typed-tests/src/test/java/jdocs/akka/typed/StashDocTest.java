/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.typed;

// #import
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.StashBuffer;
// #import

import akka.Done;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class StashDocTest extends JUnitSuite {

  // #db

  interface DB {
    CompletionStage<Done> save(String id, String value);

    CompletionStage<String> load(String id);
  }
  // #db

  // #stashing

  public static class DataAccess {

    static interface Command {}

    public static class Save implements Command {
      public final String payload;
      public final ActorRef<Done> replyTo;

      public Save(String payload, ActorRef<Done> replyTo) {
        this.payload = payload;
        this.replyTo = replyTo;
      }
    }

    public static class Get implements Command {
      public final ActorRef<String> replyTo;

      public Get(ActorRef<String> replyTo) {
        this.replyTo = replyTo;
      }
    }

    static class InitialState implements Command {
      public final String value;

      InitialState(String value) {
        this.value = value;
      }
    }

    enum SaveSuccess implements Command {
      INSTANCE
    }

    static class DBError implements Command {
      public final RuntimeException cause;

      public DBError(RuntimeException cause) {
        this.cause = cause;
      }
    }

    private final ActorContext<Command> context;
    private final StashBuffer<Command> buffer = StashBuffer.create(100);
    private final String id;
    private final DB db;

    private DataAccess(ActorContext<Command> context, String id, DB db) {
      this.context = context;
      this.id = id;
      this.db = db;
    }

    public static Behavior<Command> create(String id, DB db) {
      return Behaviors.setup(
          ctx -> {
            ctx.pipeToSelf(
                db.load(id),
                (value, cause) -> {
                  if (cause == null) return new InitialState(value);
                  else return new DBError(asRuntimeException(cause));
                });

            return new DataAccess(ctx, id, db).init();
          });
    }

    private Behavior<Command> init() {
      return Behaviors.receive(Command.class)
          .onMessage(
              InitialState.class,
              message -> {
                // now we are ready to handle stashed messages if any
                return buffer.unstashAll(context, active(message.value));
              })
          .onMessage(
              DBError.class,
              message -> {
                throw message.cause;
              })
          .onMessage(
              Command.class,
              message -> {
                // stash all other messages for later processing
                buffer.stash(message);
                return Behaviors.same();
              })
          .build();
    }

    private Behavior<Command> active(String state) {
      return Behaviors.receive(Command.class)
          .onMessage(
              Get.class,
              message -> {
                message.replyTo.tell(state);
                return Behaviors.same();
              })
          .onMessage(
              Save.class,
              message -> {
                context.pipeToSelf(
                    db.save(id, message.payload),
                    (value, cause) -> {
                      if (cause == null) return SaveSuccess.INSTANCE;
                      else return new DBError(asRuntimeException(cause));
                    });
                return saving(message.payload, message.replyTo);
              })
          .build();
    }

    private Behavior<Command> saving(String state, ActorRef<Done> replyTo) {
      return Behaviors.receive(Command.class)
          .onMessage(
              SaveSuccess.class,
              message -> {
                replyTo.tell(Done.getInstance());
                return buffer.unstashAll(context, active(state));
              })
          .onMessage(
              DBError.class,
              message -> {
                throw message.cause;
              })
          .onMessage(
              Command.class,
              message -> {
                buffer.stash(message);
                return Behaviors.same();
              })
          .build();
    }

    private static RuntimeException asRuntimeException(Throwable t) {
      // can't throw Throwable in lambdas
      if (t instanceof RuntimeException) {
        return (RuntimeException) t;
      } else {
        return new RuntimeException(t);
      }
    }
  }

  // #stashing

  @ClassRule public static final TestKitJunitResource testKit = new TestKitJunitResource();

  @Test
  public void stashingExample() throws Exception {
    final DB db =
        new DB() {
          public CompletionStage<Done> save(String id, String value) {
            return CompletableFuture.completedFuture(Done.getInstance());
          }

          public CompletionStage<String> load(String id) {
            return CompletableFuture.completedFuture("TheValue");
          }
        };

    final ActorRef<DataAccess.Command> dataAccess = testKit.spawn(DataAccess.create("17", db));
    TestProbe<String> getInbox = testKit.createTestProbe(String.class);
    dataAccess.tell(new DataAccess.Get(getInbox.getRef()));
    getInbox.expectMessage("TheValue");

    TestProbe<Done> saveInbox = testKit.createTestProbe(Done.class);
    dataAccess.tell(new DataAccess.Save("UpdatedValue", saveInbox.getRef()));
    dataAccess.tell(new DataAccess.Get(getInbox.getRef()));
    saveInbox.expectMessage(Done.getInstance());
    getInbox.expectMessage("UpdatedValue");

    dataAccess.tell(new DataAccess.Get(getInbox.getRef()));
    getInbox.expectMessage("UpdatedValue");
  }
}
