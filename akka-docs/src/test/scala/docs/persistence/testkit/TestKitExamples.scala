/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.persistence.testkit

import akka.actor.typed.ActorSystem
import akka.persistence.testkit._
import akka.persistence.testkit.scaladsl.PersistenceTestKit
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior }
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike

class TestKitExamples {

  //#testkit-typed-usecase
  class TypedSampleSpec extends AnyWordSpecLike with BeforeAndAfterAll {

    val system: ActorSystem[Cmd] = ActorSystem(
      EventSourcedBehavior[Cmd, Evt, State](
        persistenceId = ???,
        eventHandler = ???,
        commandHandler = (_, cmd) => Effect.persist(Evt(cmd.data)),
        emptyState = ???),
      "name",
      PersistenceTestKitPlugin.config.withFallback(ConfigFactory.defaultApplication()))
    val persistenceTestKit = PersistenceTestKit(system)

    override def beforeAll(): Unit =
      persistenceTestKit.clearAll()

    "Persistent actor" should {

      "persist all events" in {

        val persistentActor = system
        val cmd = Cmd("data")

        persistentActor ! cmd

        val expectedPersistedEvent = Evt(cmd.data)
        persistenceTestKit.expectNextPersisted("your-persistence-id", expectedPersistedEvent)
      }

    }
  }
  //#testkit-typed-usecase

  //#set-event-storage-policy
  class SampleEventStoragePolicy extends EventStorage.JournalPolicies.PolicyType {

    //you can use internal state, it does not need to be thread safe
    var count = 1

    override def tryProcess(persistenceId: String, processingUnit: JournalOperation): ProcessingResult =
      if (count < 10) {
        count += 1
        //check the type of operation and react with success or with reject or with failure.
        //if you return ProcessingSuccess the operation will be performed, otherwise not.
        processingUnit match {
          case ReadEvents(batch) if batch.nonEmpty => ProcessingSuccess
          case WriteEvents(batch) if batch.size > 1 =>
            ProcessingSuccess
          case ReadSeqNum      => StorageFailure()
          case DeleteEvents(_) => Reject()
          case _               => StorageFailure()
        }
      } else {
        ProcessingSuccess
      }

  }
  //#set-event-storage-policy

  //#set-snapshot-storage-policy
  class SampleSnapshotStoragePolicy extends SnapshotStorage.SnapshotPolicies.PolicyType {

    //you can use internal state, it does not need to be thread safe
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

case class Cmd(data: String)
case class Evt(data: String)
trait State
