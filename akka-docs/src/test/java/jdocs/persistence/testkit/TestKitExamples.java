/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.persistence.testkit;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.persistence.AbstractPersistentActor;
import akka.persistence.testkit.DeleteMessages;
import akka.persistence.testkit.DeleteSnapshotsByCriteria;
import akka.persistence.testkit.DeleteSnapshotByMeta;
import akka.persistence.testkit.JournalOperation;
import akka.persistence.testkit.PersistenceTestKitPlugin;
import akka.persistence.testkit.ProcessingPolicy;
import akka.persistence.testkit.ProcessingResult;
import akka.persistence.testkit.ProcessingSuccess;
import akka.persistence.testkit.ReadMessages;
import akka.persistence.testkit.ReadSeqNum;
import akka.persistence.testkit.ReadSnapshot;
import akka.persistence.testkit.Reject;
import akka.persistence.testkit.SnapshotOperation;
import akka.persistence.testkit.StorageFailure;
import akka.persistence.testkit.WriteMessages;
import akka.persistence.testkit.WriteSnapshot;
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
  class SampleMessageStoragePolicy implements ProcessingPolicy<JournalOperation> {

    // you can use internal state, it need not to be thread safe
    int count = 1;

    @Override
    public ProcessingResult tryProcess(String persistenceId, JournalOperation processingUnit) {
      // check the type of operation and react with success or with reject or with failure.
      // if you return ProcessingSuccess the operation will be performed, otherwise not.
      if (count < 10) {
        count += 1;
        if (processingUnit instanceof ReadMessages) {
          ReadMessages read = (ReadMessages) processingUnit;
          if (read.batch().nonEmpty()) {
            ProcessingSuccess.getInstance();
          } else {
            return StorageFailure.create();
          }
        } else if (processingUnit instanceof WriteMessages) {
          return ProcessingSuccess.getInstance();
        } else if (processingUnit instanceof DeleteMessages) {
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
  // #set-message-storage-policy

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
