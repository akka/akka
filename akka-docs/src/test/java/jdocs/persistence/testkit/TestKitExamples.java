/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.persistence.testkit;

import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.typed.ActorRef;
import akka.persistence.testkit.DeleteEvents;
import akka.persistence.testkit.DeleteSnapshotByMeta;
import akka.persistence.testkit.DeleteSnapshotsByCriteria;
import akka.persistence.testkit.JournalOperation;
import akka.persistence.testkit.PersistenceTestKitPlugin;
import akka.persistence.testkit.ProcessingPolicy;
import akka.persistence.testkit.ProcessingResult;
import akka.persistence.testkit.ProcessingSuccess;
import akka.persistence.testkit.ReadEvents;
import akka.persistence.testkit.ReadSeqNum;
import akka.persistence.testkit.ReadSnapshot;
import akka.persistence.testkit.Reject;
import akka.persistence.testkit.SnapshotOperation;
import akka.persistence.testkit.StorageFailure;
import akka.persistence.testkit.WriteEvents;
import akka.persistence.testkit.WriteSnapshot;
import akka.persistence.testkit.javadsl.PersistenceTestKit;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.javadsl.CommandHandler;
import akka.persistence.typed.javadsl.EventHandler;
import akka.persistence.typed.javadsl.EventSourcedBehavior;
import com.typesafe.config.ConfigFactory;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

public class TestKitExamples {

  // #set-event-storage-policy
  class SampleEventStoragePolicy implements ProcessingPolicy<JournalOperation> {

    // you can use internal state, it does not need to be thread safe
    int count = 1;

    @Override
    public ProcessingResult tryProcess(String persistenceId, JournalOperation processingUnit) {
      // check the type of operation and react with success or with reject or with failure.
      // if you return ProcessingSuccess the operation will be performed, otherwise not.
      if (count < 10) {
        count += 1;
        if (processingUnit instanceof ReadEvents) {
          ReadEvents read = (ReadEvents) processingUnit;
          if (read.batch().nonEmpty()) {
            ProcessingSuccess.getInstance();
          } else {
            return StorageFailure.create();
          }
        } else if (processingUnit instanceof WriteEvents) {
          return ProcessingSuccess.getInstance();
        } else if (processingUnit instanceof DeleteEvents) {
          return ProcessingSuccess.getInstance();
        } else if (processingUnit.equals(ReadSeqNum.getInstance())) {
          return Reject.create();
        }
        // you can set your own exception
        return StorageFailure.create(new RuntimeException("your exception"));
      } else {
        return ProcessingSuccess.getInstance();
      }
    }
  }
  // #set-event-storage-policy

  // #set-snapshot-storage-policy
  class SnapshotStoragePolicy implements ProcessingPolicy<SnapshotOperation> {

    // you can use internal state, it doesn't need to be thread safe
    int count = 1;

    @Override
    public ProcessingResult tryProcess(String persistenceId, SnapshotOperation processingUnit) {
      // check the type of operation and react with success or with failure.
      // if you return ProcessingSuccess the operation will be performed, otherwise not.
      if (count < 10) {
        count += 1;
        if (processingUnit instanceof ReadSnapshot) {
          ReadSnapshot read = (ReadSnapshot) processingUnit;
          if (read.getSnapshot().isPresent()) {
            ProcessingSuccess.getInstance();
          } else {
            return StorageFailure.create();
          }
        } else if (processingUnit instanceof WriteSnapshot) {
          return ProcessingSuccess.getInstance();
        } else if (processingUnit instanceof DeleteSnapshotsByCriteria) {
          return ProcessingSuccess.getInstance();
        } else if (processingUnit instanceof DeleteSnapshotByMeta) {
          return ProcessingSuccess.getInstance();
        }
        // you can set your own exception
        return StorageFailure.create(new RuntimeException("your exception"));
      } else {
        return ProcessingSuccess.getInstance();
      }
    }
  }
  // #set-snapshot-storage-policy

}

// #testkit-typed-usecase
class SampleTest {

  @ClassRule
  public static final TestKitJunitResource testKit =
      new TestKitJunitResource(
          PersistenceTestKitPlugin.getInstance()
              .config()
              .withFallback(ConfigFactory.defaultApplication()));

  PersistenceTestKit persistenceTestKit = PersistenceTestKit.create(testKit.system());

  @Before
  void beforeAll() {
    persistenceTestKit.clearAll();
  }

  @Test
  void test() {
    ActorRef<Cmd> ref =
        testKit.spawn(new YourPersistentBehavior(PersistenceId.ofUniqueId("some-id")));

    Cmd cmd = new Cmd("data");
    ref.tell(cmd);
    Evt expectedEventPersisted = new Evt(cmd.data);

    persistenceTestKit.expectNextPersisted("your-persistence-id", expectedEventPersisted);
  }
}

final class Cmd {

  public final String data;

  public Cmd(String data) {
    this.data = data;
  }
}

final class Evt {

  public final String data;

  public Evt(String data) {
    this.data = data;
  }
}

final class State {}

class YourPersistentBehavior extends EventSourcedBehavior<Cmd, Evt, State> {

  public YourPersistentBehavior(PersistenceId persistenceId) {
    super(persistenceId);
  }

  @Override
  public State emptyState() {
    // some state
    return new State();
  }

  @Override
  public CommandHandler<Cmd, Evt, State> commandHandler() {
    return newCommandHandlerBuilder()
        .forAnyState()
        .onCommand(Cmd.class, command -> Effect().persist(new Evt(command.data)))
        .build();
  }

  @Override
  public EventHandler<State, Evt> eventHandler() {
    // TODO handle events
    return newEventHandlerBuilder().build();
  }
}
// #testkit-typed-usecase
