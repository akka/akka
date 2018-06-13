/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import java.io.NotSerializableException

import akka.persistence.CapabilityFlag
import akka.persistence.journal.JournalSpec
import akka.persistence.snapshot.SnapshotStoreSpec
import akka.persistence.testkit.scaladsl.InMemStorageEmulator.JournalPolicy
import akka.persistence.testkit.scaladsl.ProcessingPolicy.{ ProcessingSuccess, Reject }

import scala.collection.immutable

class PersistenceTestkitJournalCompatSpec extends JournalSpec(config = PersistenceTestKitPlugin.PersitenceTestkitJournalConfig) {

  override def beforeAll(): Unit = {
    super.beforeAll()
    InMemStorageExtension(system).setWritingPolicy(new JournalPolicy {
      override def tryProcess(persistenceId: String, batch: immutable.Seq[Any]): ProcessingPolicy.ProcessingResult = {
        val allSerializable =
          batch
            .filter(_.isInstanceOf[AnyRef])
            .forall(_.isInstanceOf[java.io.Serializable])
        if (allSerializable) {
          ProcessingSuccess
        } else {
          Reject(new NotSerializableException("Some objects in the batch were not serializable"))
        }
      }
    })
  }

  override protected def supportsRejectingNonSerializableObjects: CapabilityFlag = true
}

class PersistenceTestKitSnapshotStoreCompatSpec extends SnapshotStoreSpec(config = PersistenceTestKitSnapshotPlugin.PersitenceTestkitSnapshotStoreConfig)
