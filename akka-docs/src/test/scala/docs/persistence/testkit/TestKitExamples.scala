/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.persistence.testkit

import akka.actor.{ ActorSystem, Props }
import akka.persistence.PersistentActor
import akka.persistence.testkit.ProcessingPolicy.{ ProcessingSuccess, Reject, StorageFailure }
import akka.persistence.testkit.{ MessageStorage, PersistenceTestKitPlugin, ProcessingPolicy, SnapshotStorage }
import akka.persistence.testkit.scaladsl.PersistenceTestKit
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

class TestKitExamples {

  //#testkit-usecase
  class SampleSpec extends WordSpecLike {

    implicit val system: ActorSystem =
      ActorSystem(
        "example",
        PersistenceTestKitPlugin.config
          .withFallback(ConfigFactory.defaultApplication())
      )

    val testKit = new PersistenceTestKit

    "Persistent actor" should {

      "persist all messages" in {

        val persistentActor = system.actorOf(Props[YourPersistentActor]())
        val msg = Msg("data")

        persistentActor ! msg

        testKit.expectNextPersisted("your-persistence-id", msg)

      }

    }
  }

  //#testkit-usecase

  //#set-message-storage-policy
  class SampleMessageStoragePolicy extends MessageStorage.JournalPolicies.PolicyType {

    //you can use internal state, it need not to be thread safe
    var count = 1

    override def tryProcess(
      persistenceId:  String,
      processingUnit: MessageStorage.JournalOperation
    ): ProcessingPolicy.ProcessingResult =
      if (count < 10) {
        count += 1
        //check the type of operation and react with success or with reject or with failure.
        //if you return ProcessingSuccess the operation will be performed, otherwise not.
        processingUnit match {
          case MessageStorage.Read(batch) if batch.nonEmpty ⇒ ProcessingSuccess
          case MessageStorage.Write(batch) if batch.size > 1 ⇒
            ProcessingSuccess
          case MessageStorage.ReadSeqNum ⇒ StorageFailure()
          case MessageStorage.Delete(_)  ⇒ Reject()
          case _                         ⇒ StorageFailure()
        }
      } else {
        ProcessingSuccess
      }

  }
  //#set-message-storage-policy

  //#set-snapshot-storage-policy
  class SampleSnapshotStoragePolicy extends SnapshotStorage.SnapshotPolicies.PolicyType {

    //you can use internal state, it need not to be thread safe
    var count = 1

    override def tryProcess(
      persistenceId:  String,
      processingUnit: SnapshotStorage.SnapshotOperation
    ): ProcessingPolicy.ProcessingResult =
      if (count < 10) {
        count += 1
        //check the type of operation and react with success or with reject or with failure.
        //if you return ProcessingSuccess the operation will be performed, otherwise not.
        processingUnit match {
          case SnapshotStorage.Read(_, payload) if payload.nonEmpty ⇒
            ProcessingSuccess
          case SnapshotStorage.Write(meta, payload) if meta.sequenceNr > 10 ⇒
            ProcessingSuccess
          case SnapshotStorage.DeleteByCriteria(_) ⇒ StorageFailure()
          case SnapshotStorage.DeleteSnapshot(meta) if meta.sequenceNr < 10 ⇒
            ProcessingSuccess
          case _ ⇒ StorageFailure()
        }
      } else {
        ProcessingSuccess
      }
  }
  //#set-snapshot-storage-policy

}

case class Msg(data: String)

class YourPersistentActor extends PersistentActor {

  override def receiveRecover: Receive = ???

  override def receiveCommand: Receive = ???

  override def persistenceId: String = "example-pid"
}
