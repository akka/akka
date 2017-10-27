/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.persistence.testkit;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.persistence.AbstractPersistentActor;
import akka.persistence.testkit.MessageStorage;
import akka.persistence.testkit.PersistenceTestKitPlugin;
import akka.persistence.testkit.ProcessingPolicy;
import akka.persistence.testkit.SnapshotStorage;
import akka.persistence.testkit.javadsl.PersistenceTestKit;
import com.typesafe.config.ConfigFactory;
import jdocs.AbstractJavaTest;
import org.junit.Test;

public class TestKitExamples {

  // #testkit-usecase
  class SampleTest extends AbstractJavaTest {

    ActorSystem system =
        ActorSystem.create(
            "example",
            PersistenceTestKitPlugin.getInstance()
                .config()
                .withFallback(ConfigFactory.defaultApplication()));

    @Test
    void test() {
      PersistenceTestKit testKit = new PersistenceTestKit(system);

      final Props props = Props.create(YourPersistentActor.class);
      final ActorRef actor = system.actorOf(props);

      Msg msg = new Msg("data");

      actor.tell(msg, null);

      testKit.expectNextPersisted("your-persistence-id", msg);

      testKit.clearAll();
    }
  }
  // #testkit-usecase

  // #set-message-storage-policy
  class SampleMessageStoragePolicy implements ProcessingPolicy<MessageStorage.JournalOperation> {

    // you can use internal state, it need not to be thread safe
    int count = 1;

    @Override
    public ProcessingResult tryProcess(
        String persistenceId, MessageStorage.JournalOperation processingUnit) {
      // check the type of operation and react with success or with reject or with failure.
      // if you return ProcessingSuccess the operation will be performed, otherwise not.
      if (count < 10) {
        count += 1;
        if (processingUnit instanceof MessageStorage.Read) {
          MessageStorage.Read read = (MessageStorage.Read) processingUnit;
          if (read.batch().nonEmpty()) {
            ProcessingSuccess.getInstance();
          } else {
            return StorageFailure.create();
          }
        } else if (processingUnit instanceof MessageStorage.Write) {
          return ProcessingSuccess.getInstance();
        } else if (processingUnit instanceof MessageStorage.Delete) {
          return ProcessingSuccess.getInstance();
        } else if (processingUnit.equals(MessageStorage.ReadSeqNum.getInstance())) {
          return Reject.create();
        }
        // you can set your own exception
        return StorageFailure.create(new RuntimeException("your exception"));
      } else {
        return ProcessingSuccess.getInstance();
      }
    }
  }
  // #set-message-storage-policy

  // #set-snapshot-storage-policy
  class SnapshotStoragePolicy implements ProcessingPolicy<SnapshotStorage.SnapshotOperation> {

    // you can use internal state, it need not to be thread safe
    int count = 1;

    @Override
    public ProcessingResult tryProcess(
        String persistenceId, SnapshotStorage.SnapshotOperation processingUnit) {
      // check the type of operation and react with success or with failure.
      // if you return ProcessingSuccess the operation will be performed, otherwise not.
      if (count < 10) {
        count += 1;
        if (processingUnit instanceof SnapshotStorage.Read) {
          SnapshotStorage.Read read = (SnapshotStorage.Read) processingUnit;
          if (read.getSnapshot().isPresent()) {
            ProcessingSuccess.getInstance();
          } else {
            return StorageFailure.create();
          }
        } else if (processingUnit instanceof SnapshotStorage.Write) {
          return ProcessingSuccess.getInstance();
        } else if (processingUnit instanceof SnapshotStorage.DeleteByCriteria) {
          return ProcessingSuccess.getInstance();
        } else if (processingUnit instanceof SnapshotStorage.DeleteSnapshot) {
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

class Msg {

  private String data;

  Msg(String data) {
    this.data = data;
  }

  public String getData() {
    return data;
  }

  public void setData(String data) {
    this.data = data;
  }
}

class YourPersistentActor extends AbstractPersistentActor {

  @Override
  public Receive createReceiveRecover() {
    return null;
  }

  @Override
  public Receive createReceive() {
    return null;
  }

  @Override
  public String persistenceId() {
    return null;
  }
}
