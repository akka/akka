/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import org.apache.bookkeeper.client.{ BookKeeper, BKException }
import BKException._
import org.apache.zookeeper.server.ZooKeeperServer

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach, Spec }

import akka.serialization._
import akka.actor._
import ActorSerialization._
import Actor._

import java.util.concurrent.{ CyclicBarrier, TimeUnit }
import java.io.File
import java.nio.ByteBuffer

import com.eaio.uuid.UUID

import scala.collection.JavaConversions._

class TransactionLogSpec extends WordSpec with MustMatchers with BeforeAndAfterAll {
  private var bookKeeper: BookKeeper = _
  private var localBookKeeper: LocalBookKeeper = _

  // synchronous API
  "A Transaction Log" should {
    "be able to record entries - synchronous" in {
      val uuid = (new UUID).toString
      val txlog = TransactionLog.newLogFor(uuid, false, null)
      val entry = "hello".getBytes("UTF-8")
      txlog.recordEntry(entry)
    }

    "be able to record and delete entries - synchronous" in {
      val uuid = (new UUID).toString
      val txlog1 = TransactionLog.newLogFor(uuid, false, null)
      val entry = "hello".getBytes("UTF-8")
      txlog1.recordEntry(entry)
      txlog1.recordEntry(entry)
      txlog1.delete
      txlog1.close
      intercept[BKNoSuchLedgerExistsException](TransactionLog.logFor(uuid, false, null))
    }

    "be able to record entries and read entries with 'entriesInRange' - synchronous" in {
      val uuid = (new UUID).toString
      val txlog1 = TransactionLog.newLogFor(uuid, false, null)
      val entry = "hello".getBytes("UTF-8")
      txlog1.recordEntry(entry)
      txlog1.recordEntry(entry)
      txlog1.close

      val txlog2 = TransactionLog.logFor(uuid, false, null)
      val entries = txlog2.entriesInRange(0, 1).map(bytes ⇒ new String(bytes, "UTF-8"))
      entries.size must equal(2)
      entries(0) must equal("hello")
      entries(1) must equal("hello")
      txlog2.close
    }

    "be able to record entries and read entries with 'entries' - synchronous" in {
      val uuid = (new UUID).toString
      val txlog1 = TransactionLog.newLogFor(uuid, false, null)
      val entry = "hello".getBytes("UTF-8")
      txlog1.recordEntry(entry)
      txlog1.recordEntry(entry)
      txlog1.recordEntry(entry)
      txlog1.recordEntry(entry)
      //      txlog1.close // should work without txlog.close

      val txlog2 = TransactionLog.logFor(uuid, false, null)
      val entries = txlog2.entries.map(bytes ⇒ new String(bytes, "UTF-8"))
      entries.size must equal(4)
      entries(0) must equal("hello")
      entries(1) must equal("hello")
      entries(2) must equal("hello")
      entries(3) must equal("hello")
      txlog2.close
    }

    "be able to record a snapshot - synchronous" in {
      val uuid = (new UUID).toString
      val txlog1 = TransactionLog.newLogFor(uuid, false, null)
      val snapshot = "snapshot".getBytes("UTF-8")
      txlog1.recordSnapshot(snapshot)
      txlog1.close
    }

    "be able to record and read a snapshot and following entries - synchronous" in {
      val uuid = (new UUID).toString
      val txlog1 = TransactionLog.newLogFor(uuid, false, null)
      val snapshot = "snapshot".getBytes("UTF-8")
      txlog1.recordSnapshot(snapshot)

      val entry = "hello".getBytes("UTF-8")
      txlog1.recordEntry(entry)
      txlog1.recordEntry(entry)
      txlog1.recordEntry(entry)
      txlog1.recordEntry(entry)
      txlog1.close

      val txlog2 = TransactionLog.logFor(uuid, false, null)
      val (snapshotAsBytes, entriesAsBytes) = txlog2.latestSnapshotAndSubsequentEntries
      new String(snapshotAsBytes.getOrElse(fail("No snapshot")), "UTF-8") must equal("snapshot")

      val entries = entriesAsBytes.map(bytes ⇒ new String(bytes, "UTF-8"))
      entries.size must equal(4)
      entries(0) must equal("hello")
      entries(1) must equal("hello")
      entries(2) must equal("hello")
      entries(3) must equal("hello")
      txlog2.close
    }

    "be able to record entries then a snapshot then more entries - and then read from the snapshot and the following entries - synchronous" in {
      val uuid = (new UUID).toString
      val txlog1 = TransactionLog.newLogFor(uuid, false, null)

      val entry = "hello".getBytes("UTF-8")
      txlog1.recordEntry(entry)
      txlog1.recordEntry(entry)
      txlog1.recordEntry(entry)

      val snapshot = "snapshot".getBytes("UTF-8")
      txlog1.recordSnapshot(snapshot)

      txlog1.recordEntry(entry)
      txlog1.recordEntry(entry)
      txlog1.close

      val txlog2 = TransactionLog.logFor(uuid, false, null)
      val (snapshotAsBytes, entriesAsBytes) = txlog2.latestSnapshotAndSubsequentEntries
      new String(snapshotAsBytes.getOrElse(fail("No snapshot")), "UTF-8") must equal("snapshot")

      val entries = entriesAsBytes.map(bytes ⇒ new String(bytes, "UTF-8"))
      entries.size must equal(2)
      entries(0) must equal("hello")
      entries(1) must equal("hello")
      txlog2.close
    }
  }

  "A Transaction Log" should {
    "be able to record entries - asynchronous" in {
      val uuid = (new UUID).toString
      val txlog = TransactionLog.newLogFor(uuid, true, null)
      val entry = "hello".getBytes("UTF-8")
      txlog.recordEntry(entry)
      Thread.sleep(200)
      txlog.close
    }

    "be able to record and delete entries - asynchronous" in {
      val uuid = (new UUID).toString
      val txlog1 = TransactionLog.newLogFor(uuid, true, null)
      Thread.sleep(200)
      val entry = "hello".getBytes("UTF-8")
      txlog1.recordEntry(entry)
      Thread.sleep(200)
      txlog1.recordEntry(entry)
      Thread.sleep(200)
      txlog1.delete
      Thread.sleep(200)
      intercept[BKNoSuchLedgerExistsException](TransactionLog.logFor(uuid, true, null))
    }
    "be able to record entries and read entries with 'entriesInRange' - asynchronous" in {
      val uuid = (new UUID).toString
      val txlog1 = TransactionLog.newLogFor(uuid, true, null)
      Thread.sleep(200)
      val entry = "hello".getBytes("UTF-8")
      txlog1.recordEntry(entry)
      Thread.sleep(200)
      txlog1.recordEntry(entry)
      Thread.sleep(200)
      txlog1.close

      val txlog2 = TransactionLog.logFor(uuid, true, null)
      Thread.sleep(200)
      val entries = txlog2.entriesInRange(0, 1).map(bytes ⇒ new String(bytes, "UTF-8"))
      Thread.sleep(200)
      entries.size must equal(2)
      entries(0) must equal("hello")
      entries(1) must equal("hello")
      Thread.sleep(200)
      txlog2.close
    }

    "be able to record entries and read entries with 'entries' - asynchronous" in {
      val uuid = (new UUID).toString
      val txlog1 = TransactionLog.newLogFor(uuid, true, null)
      Thread.sleep(200)
      val entry = "hello".getBytes("UTF-8")
      txlog1.recordEntry(entry)
      Thread.sleep(200)
      txlog1.recordEntry(entry)
      Thread.sleep(200)
      txlog1.recordEntry(entry)
      Thread.sleep(200)
      txlog1.recordEntry(entry)
      Thread.sleep(200)
      txlog1.close

      val txlog2 = TransactionLog.logFor(uuid, true, null)
      val entries = txlog2.entries.map(bytes ⇒ new String(bytes, "UTF-8"))
      Thread.sleep(200)
      entries.size must equal(4)
      entries(0) must equal("hello")
      entries(1) must equal("hello")
      entries(2) must equal("hello")
      entries(3) must equal("hello")
      Thread.sleep(200)
      txlog2.close
    }

    "be able to record a snapshot - asynchronous" in {
      val uuid = (new UUID).toString
      val txlog1 = TransactionLog.newLogFor(uuid, true, null)
      Thread.sleep(200)
      val snapshot = "snapshot".getBytes("UTF-8")
      txlog1.recordSnapshot(snapshot)
      Thread.sleep(200)
      txlog1.close
    }

    "be able to record and read a snapshot and following entries - asynchronous" in {
      val uuid = (new UUID).toString
      val txlog1 = TransactionLog.newLogFor(uuid, true, null)
      Thread.sleep(200)
      val snapshot = "snapshot".getBytes("UTF-8")
      txlog1.recordSnapshot(snapshot)
      Thread.sleep(200)

      val entry = "hello".getBytes("UTF-8")
      txlog1.recordEntry(entry)
      Thread.sleep(200)
      txlog1.recordEntry(entry)
      Thread.sleep(200)
      txlog1.recordEntry(entry)
      Thread.sleep(200)
      txlog1.recordEntry(entry)
      Thread.sleep(200)
      txlog1.close

      val txlog2 = TransactionLog.logFor(uuid, true, null)
      Thread.sleep(200)
      val (snapshotAsBytes, entriesAsBytes) = txlog2.latestSnapshotAndSubsequentEntries
      Thread.sleep(200)
      new String(snapshotAsBytes.getOrElse(fail("No snapshot")), "UTF-8") must equal("snapshot")

      val entries = entriesAsBytes.map(bytes ⇒ new String(bytes, "UTF-8"))
      Thread.sleep(200)
      entries.size must equal(4)
      entries(0) must equal("hello")
      entries(1) must equal("hello")
      entries(2) must equal("hello")
      entries(3) must equal("hello")
      Thread.sleep(200)
      txlog2.close
    }

    "be able to record entries then a snapshot then more entries - and then read from the snapshot and the following entries - asynchronous" in {
      val uuid = (new UUID).toString
      val txlog1 = TransactionLog.newLogFor(uuid, true, null)
      Thread.sleep(200)

      val entry = "hello".getBytes("UTF-8")
      txlog1.recordEntry(entry)
      Thread.sleep(200)
      txlog1.recordEntry(entry)
      Thread.sleep(200)
      txlog1.recordEntry(entry)
      Thread.sleep(200)

      val snapshot = "snapshot".getBytes("UTF-8")
      txlog1.recordSnapshot(snapshot)
      Thread.sleep(200)
      txlog1.recordEntry(entry)
      Thread.sleep(200)
      txlog1.recordEntry(entry)
      Thread.sleep(200)
      txlog1.close

      val txlog2 = TransactionLog.logFor(uuid, true, null)
      Thread.sleep(200)
      val (snapshotAsBytes, entriesAsBytes) = txlog2.latestSnapshotAndSubsequentEntries
      Thread.sleep(200)
      new String(snapshotAsBytes.getOrElse(fail("No snapshot")), "UTF-8") must equal("snapshot")
      val entries = entriesAsBytes.map(bytes ⇒ new String(bytes, "UTF-8"))
      Thread.sleep(200)
      entries.size must equal(2)
      entries(0) must equal("hello")
      entries(1) must equal("hello")
      Thread.sleep(200)
      txlog2.close
    }
  }

  override def beforeAll() = {
    LocalBookKeeperEnsemble.start()
  }

  override def afterAll() = {
    TransactionLog.shutdown()
    LocalBookKeeperEnsemble.shutdown()
  }
}
