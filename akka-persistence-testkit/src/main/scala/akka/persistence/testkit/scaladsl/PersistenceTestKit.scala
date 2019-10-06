/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import akka.actor.{ ActorSystem, ExtendedActorSystem, Extension, ExtensionId }
import akka.persistence.testkit.{ ExpectedFailure, ExpectedRejection }
import akka.persistence.testkit._
import akka.persistence.{ Persistence, PersistentRepr, SnapshotMetadata }
import akka.testkit.TestProbe
import com.typesafe.config.Config

import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

private[testkit] trait CommonTestKitOps[S, P] extends ClearOps with PolicyOpsTestKit[P] {
  this: HasStorage[P, S] =>

  /**
   * Check that nothing has been saved in the storage.
   */
  def expectNothingPersisted(persistenceId: String): Unit

  /**
   * Check for `max` time that nothing has been saved in the storage.
   */
  def expectNothingPersisted(persistenceId: String, max: FiniteDuration): Unit

  /**
   * Check that `msg` message has been saved in the storage.
   */
  def expectNextPersisted[A](persistenceId: String, msg: A): A

  /**
   * Check for `max` time that `msg` message has been saved in the storage.
   */
  def expectNextPersisted[A](persistenceId: String, msg: A, max: FiniteDuration): A

  /**
   * Fail next `n` persisted messages with the `cause` exception for particular persistence id.
   */
  def failNextNPersisted(persistenceId: String, n: Int, cause: Throwable): Unit

  /**
   * Fail next `n` persisted messages for particular persistence id.
   */
  def failNextNPersisted(persistenceId: String, n: Int): Unit = failNextNPersisted(persistenceId, n, ExpectedFailure)

  /**
   * Fail next `n` persisted messages with the `cause` exception for any persistence id.
   */
  def failNextNPersisted(n: Int, cause: Throwable): Unit

  /**
   * Fail next `n` persisted messages with default exception for any persistence id.
   */
  def failNextNPersisted(n: Int): Unit = failNextNPersisted(n, ExpectedFailure)

  /**
   * Fail next persisted message with `cause` exception for particular persistence id.
   */
  def failNextPersisted(persistenceId: String, cause: Throwable): Unit = failNextNPersisted(persistenceId, 1, cause)

  /**
   * Fail next persisted message with default exception for particular persistence id.
   */
  def failNextPersisted(persistenceId: String): Unit = failNextNPersisted(persistenceId, 1)

  /**
   * Fail next persisted message with `cause` exception for any persistence id.
   */
  def failNextPersisted(cause: Throwable): Unit = failNextNPersisted(1, cause)

  /**
   * Fail next persisted message with default exception for any persistence id.
   */
  def failNextPersisted(): Unit = failNextNPersisted(1)

  /**
   * Fail next read from storage (recovery) attempt with `cause` exception for any persistence id.
   */
  def failNextRead(cause: Throwable): Unit = failNextNReads(1, cause)

  /**
   * Fail next read from storage (recovery) attempt with default exception for any persistence id.
   */
  def failNextRead(): Unit = failNextNReads(1)

  /**
   * Fail next read from storage (recovery) attempt with `cause` exception for particular persistence id.
   */
  def failNextRead(persistenceId: String, cause: Throwable): Unit = failNextNReads(persistenceId, 1, cause)

  /**
   * Fail next read from storage (recovery) attempt with default exception for any persistence id.
   */
  def failNextRead(persistenceId: String): Unit = failNextNReads(persistenceId, 1)

  /**
   * Fail next n read from storage (recovery) attempts with `cause` exception for any persistence id.
   */
  def failNextNReads(n: Int, cause: Throwable): Unit

  /**
   * Fail next n read from storage (recovery) attempts with default exception for any persistence id.
   */
  def failNextNReads(n: Int): Unit = failNextNReads(n, ExpectedFailure)

  /**
   * Fail next n read from storage (recovery) attempts with `cause` exception for particular persistence id.
   */
  def failNextNReads(persistenceId: String, n: Int, cause: Throwable): Unit

  /**
   * Fail next n read from storage (recovery) attempts with default exception for particular persistence id.
   */
  def failNextNReads(persistenceId: String, n: Int): Unit = failNextNReads(persistenceId, n, ExpectedFailure)

  /**
   * Fail next delete from storage attempt with `cause` exception for any persistence id.
   */
  def failNextDelete(cause: Throwable): Unit = failNextNDeletes(1, cause)

  /**
   * Fail next delete from storage attempt with default exception for any persistence id.
   */
  def failNextDelete(): Unit = failNextNDeletes(1)

  /**
   * Fail next delete from storage attempt with `cause` exception for particular persistence id.
   */
  def failNextDelete(persistenceId: String, cause: Throwable): Unit = failNextNDeletes(persistenceId, 1, cause)

  /**
   * Fail next delete from storage attempt with default exception for particular persistence id.
   */
  def failNextDelete(persistenceId: String): Unit = failNextNDeletes(persistenceId, 1)

  /**
   * Fail next n delete from storage attempts with `cause` exception for any persistence id.
   */
  def failNextNDeletes(n: Int, cause: Throwable): Unit

  /**
   * Fail next n delete from storage attempts with default exception for any persistence id.
   */
  def failNextNDeletes(n: Int): Unit = failNextNDeletes(n, ExpectedFailure)

  /**
   * Fail next n delete from storage attempts with `cause` exception for particular persistence id.
   */
  def failNextNDeletes(persistenceId: String, n: Int, cause: Throwable): Unit

  /**
   * Fail next n delete from storage attempts with default exception for particular persistence id.
   */
  def failNextNDeletes(persistenceId: String, n: Int): Unit = failNextNDeletes(persistenceId, n, ExpectedFailure)

}

private[testkit] trait PersistenceTestKitOps[S, P]
    extends RejectSupport[P]
    with ClearPreservingSeqNums
    with CommonTestKitOps[S, P] {
  this: HasStorage[P, S] =>

  /**
   * Reject next n save in storage operations for particular persistence id with `cause` exception.
   */
  def rejectNextNPersisted(persistenceId: String, n: Int, cause: Throwable): Unit

  /**
   * Reject next n save in storage operations for particular persistence id with default exception.
   */
  def rejectNextNPersisted(persistenceId: String, n: Int): Unit =
    rejectNextNPersisted(persistenceId, n, ExpectedRejection)

  /**
   * Reject next n save in storage operations for any persistence id with default exception.
   */
  def rejectNextNPersisted(n: Int): Unit = rejectNextNPersisted(n, ExpectedRejection)

  /**
   * Reject next n save in storage operations for any persistence id with `cause` exception.
   */
  def rejectNextNPersisted(n: Int, cause: Throwable): Unit

  /**
   * Reject next save in storage operation for particular persistence id with default exception.
   */
  def rejectNextPersisted(persistenceId: String): Unit = rejectNextNPersisted(persistenceId, 1)

  /**
   * Reject next save in storage operation for particular persistence id with `cause` exception.
   */
  def rejectNextPersisted(persistenceId: String, cause: Throwable): Unit = rejectNextNPersisted(persistenceId, 1, cause)

  /**
   * Reject next save in storage operation for any persistence id with `cause` exception.
   */
  def rejectNextPersisted(cause: Throwable): Unit = rejectNextNPersisted(1, cause)

  /**
   * Reject next save in storage operation for any persistence id with default exception.
   */
  def rejectNextPersisted(): Unit = rejectNextNPersisted(1)

  /**
   * Reject next read from storage operation for any persistence id with default exception.
   */
  def rejectNextRead(): Unit = rejectNextNReads(1)

  /**
   * Reject next read from storage operation for any persistence id with `cause` exception.
   */
  def rejectNextRead(cause: Throwable): Unit = rejectNextNReads(1, cause)

  /**
   * Reject next n read from storage operations for any persistence id with default exception.
   */
  def rejectNextNReads(n: Int): Unit = rejectNextNReads(n, ExpectedRejection)

  /**
   * Reject next n read from storage operations for any persistence id with `cause` exception.
   */
  def rejectNextNReads(n: Int, cause: Throwable): Unit

  /**
   * Reject next read from storage operation for particular persistence id with default exception.
   */
  def rejectNextRead(persistenceId: String): Unit = rejectNextNReads(persistenceId, 1)

  /**
   * Reject next read from storage operation for particular persistence id with `cause` exception.
   */
  def rejectNextRead(persistenceId: String, cause: Throwable): Unit = rejectNextNReads(persistenceId, 1, cause)

  /**
   * Reject next n read from storage operations for particular persistence id with default exception.
   */
  def rejectNextNReads(persistenceId: String, n: Int): Unit = rejectNextNReads(persistenceId, n, ExpectedRejection)

  /**
   * Reject next n read from storage operations for particular persistence id with `cause` exception.
   */
  def rejectNextNReads(persistenceId: String, n: Int, cause: Throwable): Unit

  /**
   * Reject next delete from storage operation for any persistence id with default exception.
   */
  def rejectNextDelete(): Unit = rejectNextNDeletes(1)

  /**
   * Reject next delete from storage operation for any persistence id with `cause` exception.
   */
  def rejectNextDelete(cause: Throwable): Unit = rejectNextNDeletes(1, cause)

  /**
   * Reject next n delete from storage operations for any persistence id with default exception.
   */
  def rejectNextNDeletes(n: Int): Unit = rejectNextNDeletes(n, ExpectedRejection)

  /**
   * Reject next n delete from storage operations for any persistence id with `cause` exception.
   */
  def rejectNextNDeletes(n: Int, cause: Throwable): Unit

  /**
   * Reject next delete from storage operations for particular persistence id with default exception.
   */
  def rejectNextDelete(persistenceId: String): Unit = rejectNextNDeletes(persistenceId, 1)

  /**
   * Reject next delete from storage operations for particular persistence id with `cause` exception.
   */
  def rejectNextDelete(persistenceId: String, cause: Throwable): Unit = rejectNextNDeletes(persistenceId, 1, cause)

  /**
   * Reject next n delete from storage operations for particular persistence id with default exception.
   */
  def rejectNextNDeletes(persistenceId: String, n: Int): Unit = rejectNextNDeletes(persistenceId, n, ExpectedRejection)

  /**
   * Reject next n delete from storage operations for particular persistence id with `cause` exception.
   */
  def rejectNextNDeletes(persistenceId: String, n: Int, cause: Throwable): Unit

  /**
   * Persist `elems` messages into storage in order.
   */
  def persistForRecovery(persistenceId: String, elems: immutable.Seq[Any]): Unit

  /**
   * Retrieve all messages saved in storage by persistence id.
   */
  def persistedInStorage(persistenceId: String): immutable.Seq[Any]

}

/**
 * Snapshot testkit is for testing persistent actors using snapshots.
 *
 * NOTE! ActorSystem must be configured with [[PersistenceTestKitSnapshotPlugin]].
 * The configuration can be retrieved with [[PersistenceTestKitSnapshotPlugin.config]].
 */
class SnapshotTestKit(implicit val system: ActorSystem)
    extends CommonTestKitOps[(SnapshotMetadata, Any), SnapshotOperation]
    with PolicyOpsTestKit[SnapshotOperation]
    with ExpectOps[(SnapshotMetadata, Any)]
    with HasStorage[SnapshotOperation, (SnapshotMetadata, Any)] {
  require(
    Try(Persistence(system).journalFor(PersistenceTestKitSnapshotPlugin.PluginId)).isSuccess,
    "The test persistence plugin for snapshots is not configured.")

  import SnapshotTestKit._

  override protected val storage: SnapshotStorage = SnapshotStorageEmulatorExtension(system)

  private val settings = Settings(system)

  override private[testkit] val probe = TestProbe()

  override private[testkit] val pollInterval: FiniteDuration = settings.pollInterval

  override private[testkit] val maxTimeout: FiniteDuration = settings.assertTimeout

  override private[testkit] val Policies = SnapshotStorage.SnapshotPolicies

  override def failNextNPersisted(persistenceId: String, n: Int, cause: Throwable): Unit =
    failNextNOpsCond((pid, op) => pid == persistenceId && op.isInstanceOf[WriteSnapshot], n, cause)

  override def failNextNPersisted(n: Int, cause: Throwable): Unit =
    failNextNOpsCond((_, op) => op.isInstanceOf[WriteSnapshot], n, cause)

  override def failNextNReads(n: Int, cause: Throwable): Unit =
    failNextNOpsCond((_, op) => op.isInstanceOf[ReadSnapshot], n, cause)

  override def failNextNReads(persistenceId: String, n: Int, cause: Throwable): Unit =
    failNextNOpsCond((pid, op) => pid == persistenceId && op.isInstanceOf[ReadSnapshot], n, cause)

  override def failNextNDeletes(n: Int, cause: Throwable): Unit =
    failNextNOpsCond((_, op) => op.isInstanceOf[DeleteSnapshot], n, cause)

  override def failNextNDeletes(persistenceId: String, n: Int, cause: Throwable): Unit =
    failNextNOpsCond((pid, op) => pid == persistenceId && op.isInstanceOf[DeleteSnapshot], n, cause)

  /**
   * Persist `elems` pairs of (snapshot metadata, snapshot payload) into storage.
   */
  def persistForRecovery(persistenceId: String, elems: immutable.Seq[(SnapshotMeta, Any)]): Unit =
    elems.foreach {
      case (m, p) =>
        storage.add(persistenceId, (SnapshotMetadata(persistenceId, m.sequenceNr, m.timestamp), p))
        addToIndex(persistenceId, 1)
    }

  /**
   * Persist a pair of (snapshot metadata, snapshot payload) into storage.
   */
  def persistForRecovery(persistenceId: String, elem: (SnapshotMeta, Any)): Unit =
    persistForRecovery(persistenceId, immutable.Seq(elem))

  /**
   * Retrieve snapshots and their metadata from storage by persistence id.
   */
  def persistedInStorage(persistenceId: String): immutable.Seq[(SnapshotMeta, Any)] =
    storage
      .read(persistenceId)
      .map(_.map(m => (SnapshotMeta(m._1.sequenceNr, m._1.timestamp), m._2)))
      .getOrElse(Vector.empty)

  override private[testkit] def reprToAny(repr: (SnapshotMetadata, Any)) = repr._2

}

object SnapshotTestKit {

  object Settings extends ExtensionId[Settings] {

    val configPath = "akka.persistence.testkit.snapshots"

    override def createExtension(system: ExtendedActorSystem): Settings =
      new Settings(system.settings.config.getConfig(configPath))

    override def get(system: ActorSystem): Settings = super.get(system)

  }

  class Settings(config: Config) extends Extension {

    import akka.util.Helpers._

    val serialize: Boolean = config.getBoolean("serialize")
    val assertTimeout: FiniteDuration = config.getMillisDuration("assert-timeout")
    val pollInterval: FiniteDuration = config.getMillisDuration("assert-poll-interval")

  }

}

/**
 * Persistence testkit for testing persistent actors.
 *
 * NOTE! ActorSystem must be configured with [[PersistenceTestKitPlugin]].
 * The configuration can be retrieved with [[PersistenceTestKitPlugin.config]].
 */
class PersistenceTestKit(implicit val system: ActorSystem)
    extends PersistenceTestKitOps[PersistentRepr, JournalOperation]
    with ExpectOps[PersistentRepr]
    with HasStorage[JournalOperation, PersistentRepr] {
  require(
    Try(Persistence(system).journalFor(PersistenceTestKitPlugin.PluginId)).isSuccess,
    "The test persistence plugin is not configured.")

  import PersistenceTestKit._

  override protected val storage = InMemStorageExtension(system)

  private final lazy val settings = Settings(system)

  override private[testkit] val probe = TestProbe()

  override private[testkit] val Policies = MessageStorage.JournalPolicies

  override private[testkit] val pollInterval: FiniteDuration = settings.pollInterval

  override private[testkit] val maxTimeout: FiniteDuration = settings.assertTimeout

  override def rejectNextNPersisted(persistenceId: String, n: Int, cause: Throwable): Unit =
    rejectNextNOpsCond((pid, op) => pid == persistenceId && op.isInstanceOf[WriteMessages], n, cause)

  override def rejectNextNPersisted(n: Int, cause: Throwable): Unit =
    rejectNextNOpsCond((_, op) => op.isInstanceOf[WriteMessages], n, cause)

  override def rejectNextNReads(n: Int, cause: Throwable): Unit =
    rejectNextNOpsCond((_, op) => op.isInstanceOf[ReadMessages] || op.isInstanceOf[ReadSeqNum.type], n, cause)

  override def rejectNextNReads(persistenceId: String, n: Int, cause: Throwable): Unit =
    rejectNextNOpsCond(
      (pid, op) => (pid == persistenceId) && (op.isInstanceOf[ReadMessages] || op.isInstanceOf[ReadSeqNum.type]),
      n,
      cause)

  override def rejectNextNDeletes(n: Int, cause: Throwable): Unit =
    rejectNextNOpsCond((_, op) => op.isInstanceOf[DeleteMessages], n, cause)

  override def rejectNextNDeletes(persistenceId: String, n: Int, cause: Throwable): Unit =
    rejectNextNOpsCond((pid, op) => pid == persistenceId && op.isInstanceOf[DeleteMessages], n, cause)

  override def failNextNPersisted(persistenceId: String, n: Int, cause: Throwable): Unit =
    failNextNOpsCond((pid, op) => pid == persistenceId && op.isInstanceOf[WriteMessages], n, cause)

  override def failNextNPersisted(n: Int, cause: Throwable): Unit =
    failNextNOpsCond((_, op) => op.isInstanceOf[WriteMessages], n, cause)

  override def failNextNReads(n: Int, cause: Throwable): Unit =
    failNextNOpsCond((_, op) => op.isInstanceOf[ReadMessages] || op.isInstanceOf[ReadSeqNum.type], n, cause)

  override def failNextNReads(persistenceId: String, n: Int, cause: Throwable): Unit =
    failNextNOpsCond(
      (pid, op) => (pid == persistenceId) && (op.isInstanceOf[ReadMessages] || op.isInstanceOf[ReadSeqNum.type]),
      n,
      cause)

  override def failNextNDeletes(n: Int, cause: Throwable): Unit =
    failNextNOpsCond((_, op) => op.isInstanceOf[DeleteMessages], n, cause)

  override def failNextNDeletes(persistenceId: String, n: Int, cause: Throwable): Unit =
    failNextNOpsCond((pid, op) => pid == persistenceId && op.isInstanceOf[DeleteMessages], n, cause)

  def persistForRecovery(persistenceId: String, elems: immutable.Seq[Any]): Unit = {
    storage.addAny(persistenceId, elems)
    addToIndex(persistenceId, elems.size)
  }

  def persistedInStorage(persistenceId: String): immutable.Seq[Any] =
    storage.read(persistenceId).getOrElse(List.empty).map(reprToAny)

  override private[testkit] def reprToAny(repr: PersistentRepr): Any = repr.payload
}

object PersistenceTestKit {

  object Settings extends ExtensionId[Settings] {

    val configPath = "akka.persistence.testkit.messages"

    override def get(system: ActorSystem): Settings = super.get(system)

    override def createExtension(system: ExtendedActorSystem): Settings =
      new Settings(system.settings.config.getConfig(configPath))

  }

  class Settings(config: Config) extends Extension {

    import akka.util.Helpers._

    val serialize: Boolean = config.getBoolean("serialize")
    val assertTimeout: FiniteDuration = config.getMillisDuration("assert-timeout")
    val pollInterval: FiniteDuration = config.getMillisDuration("assert-poll-interval")

  }

}
