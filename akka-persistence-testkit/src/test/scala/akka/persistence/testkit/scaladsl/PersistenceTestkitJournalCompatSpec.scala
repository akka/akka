/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import java.io.NotSerializableException

import akka.persistence.CapabilityFlag
import akka.persistence.journal.JournalSpec
import akka.persistence.snapshot.SnapshotStoreSpec
import akka.persistence.testkit.scaladsl.InMemStorageEmulator.{JournalOperation, JournalPolicy, Write}
import akka.persistence.testkit.scaladsl.ProcessingPolicy.{ProcessingSuccess, Reject}

class PersistenceTestkitJournalCompatSpec extends JournalSpec(config = PersistenceTestKitPlugin.PersitenceTestkitJournalConfig) {

  override def beforeAll(): Unit = {
    super.beforeAll()
    InMemStorageExtension(system).setPolicy(new JournalPolicy {
      override def tryProcess(persistenceId: String, op: JournalOperation): ProcessingPolicy.ProcessingResult = {
        op match {
          case Write(batch) => val allSerializable =
            batch
              .filter(_.isInstanceOf[AnyRef])
              .forall(_.isInstanceOf[java.io.Serializable])
            if (allSerializable) {
              ProcessingSuccess
            } else {
              Reject(new NotSerializableException("Some objects in the batch were not serializable"))
            }
          case _ => ProcessingSuccess
        }

      }
    })
  }

  override protected def supportsRejectingNonSerializableObjects: CapabilityFlag = true
}

class PersistenceTestKitSnapshotStoreCompatSpec extends SnapshotStoreSpec(config = PersistenceTestKitSnapshotPlugin.PersitenceTestkitSnapshotStoreConfig)
