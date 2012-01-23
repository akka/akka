/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import org.apache.bookkeeper.client.BookKeeper
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll

import akka.actor._
import akka.event.EventHandler
import akka.testkit.{ EventFilter, TestEvent }

import com.eaio.uuid.UUID

class SynchronousTransactionLogSpec extends WordSpec with MustMatchers with BeforeAndAfterAll {
  private var bookKeeper: BookKeeper = _
  private var localBookKeeper: LocalBookKeeper = _

  "A synchronous used Transaction Log" should {

    "be able to be deleted - synchronous" in {
      val uuid = (new UUID).toString
      val txlog = TransactionLog.newLogFor(uuid, false, null)
      val entry = "hello".getBytes("UTF-8")
      txlog.recordEntry(entry)

      txlog.delete()
      txlog.close()

      val zkClient = TransactionLog.zkClient
      assert(zkClient.readData(txlog.snapshotPath, true) == null)
      assert(zkClient.readData(txlog.txLogPath, true) == null)
    }

    "fail to be opened if non existing - synchronous" in {
      EventHandler.notify(TestEvent.Mute(EventFilter[ReplicationException]))
      val uuid = (new UUID).toString
      intercept[ReplicationException](TransactionLog.logFor(uuid, false, null))
      EventHandler.notify(TestEvent.UnMuteAll)
    }

    "be able to be checked for existence - synchronous" in {
      val uuid = (new UUID).toString
      TransactionLog.exists(uuid) must be(false)

      TransactionLog.newLogFor(uuid, false, null)
      TransactionLog.exists(uuid) must be(true)
    }

    "be able to record entries - synchronous" in {
      val uuid = (new UUID).toString
      val txlog = TransactionLog.newLogFor(uuid, false, null)
      val entry = "hello".getBytes("UTF-8")
      txlog.recordEntry(entry)
    }

    "be able to overweite an existing txlog if one already exists - synchronous" in {
      val uuid = (new UUID).toString
      val txlog1 = TransactionLog.newLogFor(uuid, false, null)
      val entry = "hello".getBytes("UTF-8")
      txlog1.recordEntry(entry)
      txlog1.recordEntry(entry)
      txlog1.close

      val txLog2 = TransactionLog.newLogFor(uuid, false, null)
      txLog2.latestSnapshotId.isDefined must be(false)
      txLog2.latestEntryId must be(-1)
    }

    "be able to record and delete entries - synchronous" in {
      val uuid = (new UUID).toString
      val txlog1 = TransactionLog.newLogFor(uuid, false, null)
      val entry = "hello".getBytes("UTF-8")
      txlog1.recordEntry(entry)
      txlog1.recordEntry(entry)
      txlog1.delete
      txlog1.close
      //      intercept[ReplicationException](TransactionLog.logFor(uuid, false, null))
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
      txlog1.close // should work without txlog.close

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

    "be able to record and read a snapshot and following entries  - synchronous" in {
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

    "be able to record entries then a snapshot then more entries - and then read from the snapshot and the following entries  - synchronous" in {
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

  override def beforeAll() = {
    LocalBookKeeperEnsemble.start()
    TransactionLog.start()
  }

  override def afterAll() = {
    TransactionLog.shutdown()
    LocalBookKeeperEnsemble.shutdown()
  }
}
