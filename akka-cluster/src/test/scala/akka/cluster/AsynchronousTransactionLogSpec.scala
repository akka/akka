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

class AsynchronousTransactionLogSpec extends WordSpec with MustMatchers with BeforeAndAfterAll {
  private var bookKeeper: BookKeeper = _
  private var localBookKeeper: LocalBookKeeper = _

  "An asynchronous Transaction Log" should {
    "be able to record entries - asynchronous" in {
      val uuid = (new UUID).toString
      val txlog = TransactionLog.newLogFor(uuid, true, null)
      val entry = "hello".getBytes("UTF-8")
      txlog.recordEntry(entry)
      Thread.sleep(200)
      txlog.close
    }

    "be able to be deleted - asynchronous" in {
      val uuid = (new UUID).toString
      val txlog = TransactionLog.newLogFor(uuid, true, null)
      val entry = "hello".getBytes("UTF-8")
      txlog.recordEntry(entry)

      txlog.delete()
      txlog.close()

      val zkClient = TransactionLog.zkClient
      assert(zkClient.readData(txlog.snapshotPath, true) == null)
      assert(zkClient.readData(txlog.txLogPath, true) == null)
    }

    "be able to be checked for existence - asynchronous" in {
      val uuid = (new UUID).toString
      TransactionLog.exists(uuid) must be(false)

      TransactionLog.newLogFor(uuid, true, null)
      TransactionLog.exists(uuid) must be(true)
    }

    "fail to be opened if non existing - asynchronous" in {
      EventHandler.notify(TestEvent.Mute(EventFilter[ReplicationException]))
      val uuid = (new UUID).toString
      intercept[ReplicationException](TransactionLog.logFor(uuid, true, null))
      EventHandler.notify(TestEvent.UnMuteAll)
    }

    "be able to overweite an existing txlog if one already exists - asynchronous" in {
      val uuid = (new UUID).toString
      val txlog1 = TransactionLog.newLogFor(uuid, true, null)
      val entry = "hello".getBytes("UTF-8")
      txlog1.recordEntry(entry)
      txlog1.recordEntry(entry)
      txlog1.close

      val txLog2 = TransactionLog.newLogFor(uuid, true, null)
      txLog2.latestSnapshotId.isDefined must be(false)
      txLog2.latestEntryId must be(-1)
    }

    "be able to record and delete entries - asynchronous" in {
      EventHandler.notify(TestEvent.Mute(EventFilter[ReplicationException]))
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
      intercept[ReplicationException](TransactionLog.logFor(uuid, true, null))
      EventHandler.notify(TestEvent.UnMuteAll)
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
    TransactionLog.start()
  }

  override def afterAll() = {
    TransactionLog.shutdown()
    LocalBookKeeperEnsemble.shutdown()
  }
}
