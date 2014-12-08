package akka.persistence.snapshot

import scala.collection.immutable.Seq

import akka.actor._
import akka.persistence._
import akka.persistence.SnapshotProtocol._
import akka.testkit.TestProbe

import com.typesafe.config.ConfigFactory

object SnapshotStoreSpec {
  val config = ConfigFactory.parseString("akka.persistence.publish-plugin-commands = on")
}

/**
 * This spec aims to verify custom akka-persistence [[SnapshotStore]] implementations.
 * Plugin authors are highly encouraged to include it in their plugin's test suites.
 *
 * In case your journal plugin needs some kind of setup or teardown, override the `beforeAll` or `afterAll`
 * methods (don't forget to call `super` in your overriden methods).
 *
 * For a Java and JUnit consumable version of the TCK please refer to [[akka.persistence.japi.snapshot.JavaSnapshotStoreSpec]].
 *
 * @see [[akka.persistence.japi.snapshot.JavaSnapshotStoreSpec]]
 */
trait SnapshotStoreSpec extends PluginSpec {
  implicit lazy val system = ActorSystem("SnapshotStoreSpec", config.withFallback(SnapshotStoreSpec.config))

  private var senderProbe: TestProbe = _
  private var metadata: Seq[SnapshotMetadata] = Nil

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    senderProbe = TestProbe()
    metadata = writeSnapshots()
  }

  def snapshotStore: ActorRef =
    extension.snapshotStoreFor(null)

  def writeSnapshots(): Seq[SnapshotMetadata] = {
    1 to 5 map { i ⇒
      val metadata = SnapshotMetadata(pid, i + 10)
      snapshotStore.tell(SaveSnapshot(metadata, s"s-${i}"), senderProbe.ref)
      senderProbe.expectMsgPF() { case SaveSnapshotSuccess(md) ⇒ md }
    }
  }

  "A snapshot store" must {
    "not load a snapshot given an invalid persistenceId" in {
      snapshotStore.tell(LoadSnapshot("invalid", SnapshotSelectionCriteria.Latest, Long.MaxValue), senderProbe.ref)
      senderProbe.expectMsg(LoadSnapshotResult(None, Long.MaxValue))
    }
    "not load a snapshot given non-matching timestamp criteria" in {
      snapshotStore.tell(LoadSnapshot(pid, SnapshotSelectionCriteria.Latest.copy(maxTimestamp = 100), Long.MaxValue), senderProbe.ref)
      senderProbe.expectMsg(LoadSnapshotResult(None, Long.MaxValue))
    }
    "not load a snapshot given non-matching sequence number criteria" in {
      snapshotStore.tell(LoadSnapshot(pid, SnapshotSelectionCriteria(7), Long.MaxValue), senderProbe.ref)
      senderProbe.expectMsg(LoadSnapshotResult(None, Long.MaxValue))
      snapshotStore.tell(LoadSnapshot(pid, SnapshotSelectionCriteria.Latest, 7), senderProbe.ref)
      senderProbe.expectMsg(LoadSnapshotResult(None, 7))
    }
    "load the most recent snapshot" in {
      snapshotStore.tell(LoadSnapshot(pid, SnapshotSelectionCriteria.Latest, Long.MaxValue), senderProbe.ref)
      senderProbe.expectMsg(LoadSnapshotResult(Some(SelectedSnapshot(metadata(4), s"s-5")), Long.MaxValue))
    }
    "load the most recent snapshot matching an upper sequence number bound" in {
      snapshotStore.tell(LoadSnapshot(pid, SnapshotSelectionCriteria(13), Long.MaxValue), senderProbe.ref)
      senderProbe.expectMsg(LoadSnapshotResult(Some(SelectedSnapshot(metadata(2), s"s-3")), Long.MaxValue))
      snapshotStore.tell(LoadSnapshot(pid, SnapshotSelectionCriteria.Latest, 13), senderProbe.ref)
      senderProbe.expectMsg(LoadSnapshotResult(Some(SelectedSnapshot(metadata(2), s"s-3")), 13))
    }
    "load the most recent snapshot matching upper sequence number and timestamp bounds" in {
      snapshotStore.tell(LoadSnapshot(pid, SnapshotSelectionCriteria(13, metadata(2).timestamp), Long.MaxValue), senderProbe.ref)
      senderProbe.expectMsg(LoadSnapshotResult(Some(SelectedSnapshot(metadata(2), s"s-3")), Long.MaxValue))
      snapshotStore.tell(LoadSnapshot(pid, SnapshotSelectionCriteria.Latest.copy(maxTimestamp = metadata(2).timestamp), 13), senderProbe.ref)
      senderProbe.expectMsg(LoadSnapshotResult(Some(SelectedSnapshot(metadata(2), s"s-3")), 13))
    }
    "delete a single snapshot identified by snapshot metadata" in {
      val md = metadata(2)
      val cmd = DeleteSnapshot(md)
      val sub = TestProbe()

      subscribe[DeleteSnapshot](sub.ref)
      snapshotStore ! cmd
      sub.expectMsg(cmd)

      snapshotStore.tell(LoadSnapshot(pid, SnapshotSelectionCriteria(md.sequenceNr, md.timestamp), Long.MaxValue), senderProbe.ref)
      senderProbe.expectMsg(LoadSnapshotResult(Some(SelectedSnapshot(metadata(1), s"s-2")), Long.MaxValue))
    }
    "delete all snapshots matching upper sequence number and timestamp bounds" in {
      val md = metadata(2)
      val cmd = DeleteSnapshots(pid, SnapshotSelectionCriteria(md.sequenceNr, md.timestamp))
      val sub = TestProbe()

      subscribe[DeleteSnapshots](sub.ref)
      snapshotStore ! cmd
      sub.expectMsg(cmd)

      snapshotStore.tell(LoadSnapshot(pid, SnapshotSelectionCriteria(md.sequenceNr, md.timestamp), Long.MaxValue), senderProbe.ref)
      senderProbe.expectMsg(LoadSnapshotResult(None, Long.MaxValue))
      snapshotStore.tell(LoadSnapshot(pid, SnapshotSelectionCriteria(metadata(3).sequenceNr, metadata(3).timestamp), Long.MaxValue), senderProbe.ref)
      senderProbe.expectMsg(LoadSnapshotResult(Some(SelectedSnapshot(metadata(3), s"s-4")), Long.MaxValue))
    }
  }
}
