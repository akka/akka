/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.persistence.testkit

import akka.actor.{ ActorSystem, Props }
import akka.persistence.PersistentActor
import akka.persistence.testkit.{ ProcessingSuccess, Reject, StorageFailure }
import akka.persistence.testkit._
import akka.persistence.testkit.scaladsl.PersistenceTestKit
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

class TestKitExamples {

  //#testkit-usecase
  class SampleSpec extends WordSpecLike {

    implicit val system: ActorSystem =
      ActorSystem("example", PersistenceTestKitPlugin.config.withFallback(ConfigFactory.defaultApplication()))

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

    override def tryProcess(persistenceId: String, processingUnit: JournalOperation): ProcessingResult =
      if (count < 10) {
        count += 1
        //check the type of operation and react with success or with reject or with failure.
        //if you return ProcessingSuccess the operation will be performed, otherwise not.
        processingUnit match {
          case ReadMessages(batch) if batch.nonEmpty => ProcessingSuccess
          case WriteMessages(batch) if batch.size > 1 =>
            ProcessingSuccess
          case ReadSeqNum        => StorageFailure()
          case DeleteMessages(_) => Reject()
          case _                 => StorageFailure()
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

    override def tryProcess(persistenceId: String, processingUnit: SnapshotOperation): ProcessingResult =
      if (count < 10) {
        count += 1
        //check the type of operation and react with success or with reject or with failure.
        //if you return ProcessingSuccess the operation will be performed, otherwise not.
        processingUnit match {
          case ReadSnapshot(_, payload) if payload.nonEmpty =>
            ProcessingSuccess
          case WriteSnapshot(meta, payload) if meta.sequenceNr > 10 =>
            ProcessingSuccess
          case DeleteSnapshotsByCriteria(_) => StorageFailure()
          case DeleteSnapshotByMeta(meta) if meta.sequenceNr < 10 =>
            ProcessingSuccess
          case _ => StorageFailure()
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
