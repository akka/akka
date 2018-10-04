/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import akka.actor.{ ActorSystem, ExtendedActorSystem, Extension, ExtensionId }
import akka.persistence.testkit.scaladsl.MessageStorage.JournalOperation
import akka.persistence.testkit.scaladsl.PolicyOpsTestKit.ExpectedFailure
import akka.persistence.testkit.scaladsl.RejectSupport.ExpectedRejection
import akka.persistence.testkit.scaladsl.SnapshotStorage.SnapshotOperation
import akka.persistence.{ Persistence, PersistentRepr, SnapshotMetadata }
import com.typesafe.config.Config

import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

trait CommonTestKitOps[S, P] extends ClearOps with PolicyOpsTestKit[P] {
  this: HasStorage[S, P] ⇒

  def expectNothingPersisted(persistenceId: String): Unit

  def expectNothingPersisted(persistenceId: String, max: FiniteDuration): Unit

  def expectNextPersisted[A](persistenceId: String, msg: A): A

  def expectNextPersisted[A](persistenceId: String, msg: A, max: FiniteDuration): A

  def failNextNPersisted(persistenceId: String, n: Int, cause: Throwable): Unit

  def failNextNPersisted(persistenceId: String, n: Int): Unit = failNextNPersisted(persistenceId, n, ExpectedFailure)

  def failNextNPersisted(n: Int, cause: Throwable): Unit

  def failNextNPersisted(n: Int): Unit = failNextNPersisted(n, ExpectedFailure)

  def failNextPersisted(persistenceId: String, cause: Throwable): Unit = failNextNPersisted(persistenceId, 1, cause)

  def failNextPersisted(persistenceId: String): Unit = failNextNPersisted(persistenceId, 1)

  def failNextPersisted(cause: Throwable): Unit = failNextNPersisted(1, cause)

  def failNextPersisted(): Unit = failNextNPersisted(1)

  def failNextRead(cause: Throwable): Unit = failNextNReads(1, cause)

  def failNextRead(): Unit = failNextNReads(1)

  def failNextRead(persistenceId: String, cause: Throwable): Unit = failNextNReads(persistenceId, 1, cause)

  def failNextRead(persistenceId: String): Unit = failNextNReads(persistenceId, 1)

  def failNextNReads(n: Int, cause: Throwable): Unit

  def failNextNReads(n: Int): Unit = failNextNReads(n, ExpectedFailure)

  def failNextNReads(persistenceId: String, n: Int, cause: Throwable): Unit

  def failNextNReads(persistenceId: String, n: Int): Unit = failNextNReads(persistenceId, n, ExpectedFailure)

  def failNextDelete(cause: Throwable): Unit = failNextNDeletes(1, cause)

  def failNextDelete(): Unit = failNextNDeletes(1)

  def failNextDelete(persistenceId: String, cause: Throwable): Unit = failNextNDeletes(persistenceId, 1, cause)

  def failNextDelete(persistenceId: String): Unit = failNextNDeletes(persistenceId, 1)

  def failNextNDeletes(n: Int, cause: Throwable): Unit

  def failNextNDeletes(n: Int): Unit = failNextNDeletes(n, ExpectedFailure)

  def failNextNDeletes(persistenceId: String, n: Int, cause: Throwable): Unit

  def failNextNDeletes(persistenceId: String, n: Int): Unit = failNextNDeletes(persistenceId, n, ExpectedFailure)

  def persistForRecovery(persistenceId: String, elems: immutable.Seq[Any]): Unit

  def persistedInStorage(persistenceId: String): immutable.Seq[Any]

}

trait PersistenceTestKitOps[S, P] extends RejectSupport[P] with CommonTestKitOps[S, P] {
  this: HasStorage[S, P] ⇒

  def expectPersistedInOrder[A](persistenceId: String, msgs: immutable.Seq[A]): immutable.Seq[A]

  def expectPersistedInOrder[A](persistenceId: String, msgs: immutable.Seq[A], max: FiniteDuration): immutable.Seq[A]

  def expectPersistedInAnyOrder[A](persistenceId: String, msgs: immutable.Seq[A]): immutable.Seq[A]

  def expectPersistedInAnyOrder[A](persistenceId: String, msgs: immutable.Seq[A], max: FiniteDuration): immutable.Seq[A]

  def rejectNextNPersisted(persistenceId: String, n: Int, cause: Throwable): Unit

  def rejectNextNPersisted(persistenceId: String, n: Int): Unit = rejectNextNPersisted(persistenceId, n, ExpectedRejection)

  def rejectNextNPersisted(n: Int): Unit = rejectNextNPersisted(n, ExpectedRejection)

  def rejectNextNPersisted(n: Int, cause: Throwable): Unit

  def rejectNextPersisted(persistenceId: String): Unit = rejectNextNPersisted(persistenceId, 1)

  def rejectNextPersisted(persistenceId: String, cause: Throwable): Unit = rejectNextNPersisted(persistenceId, 1, cause)

  def rejectNextPersisted(cause: Throwable): Unit = rejectNextNPersisted(1, cause)

  def rejectNextPersisted(): Unit = rejectNextNPersisted(1)

  def rejectNextRead(): Unit = rejectNextNReads(1)

  def rejectNextRead(cause: Throwable): Unit = rejectNextNReads(1, cause)

  def rejectNextNReads(n: Int): Unit = rejectNextNReads(n, ExpectedRejection)

  def rejectNextNReads(n: Int, cause: Throwable): Unit

  def rejectNextRead(persistenceId: String): Unit = rejectNextNReads(persistenceId, 1)

  def rejectNextRead(persistenceId: String, cause: Throwable): Unit = rejectNextNReads(persistenceId, 1, cause)

  def rejectNextNReads(persistenceId: String, n: Int): Unit = rejectNextNReads(persistenceId, n, ExpectedRejection)

  def rejectNextNReads(persistenceId: String, n: Int, cause: Throwable): Unit

  def rejectNextDelete(): Unit = rejectNextNDeletes(1)

  def rejectNextDelete(cause: Throwable): Unit = rejectNextNDeletes(1, ExpectedRejection)

  def rejectNextNDeletes(n: Int): Unit = rejectNextNDeletes(n, ExpectedRejection)

  def rejectNextNDeletes(n: Int, cause: Throwable): Unit

  def rejectNextDelete(persistenceId: String): Unit = rejectNextNDeletes(persistenceId, 1)

  def rejectNextDelete(persistenceId: String, cause: Throwable): Unit = rejectNextNDeletes(persistenceId, 1, cause)

  def rejectNextNDeletes(persistenceId: String, n: Int): Unit = rejectNextNDeletes(persistenceId, n, ExpectedRejection)

  def rejectNextNDeletes(persistenceId: String, n: Int, cause: Throwable): Unit

}

class SnapshotTestKit(override val storage: SnapshotStorage[_])(implicit val system: ActorSystem)
  extends CommonTestKitOps[(SnapshotMetadata, Any), SnapshotOperation]
  with PolicyOpsTestKit[SnapshotOperation]
  with ExpectOps[(SnapshotMetadata, Any)]
  with HasStorage[(SnapshotMetadata, Any), SnapshotOperation] {
  require(Try(Persistence(system).journalFor(PersistenceTestKitSnapshotPlugin.PluginId)).isSuccess, "The test persistence plugin for snapshots is not configured.")

  import SnapshotStorage._
  import SnapshotTestKit._

  private val settings = Settings(system)

  override private[testkit] val pollInterval: FiniteDuration = settings.pollInterval

  override private[testkit] val maxTimeout: FiniteDuration = settings.assertTimeout

  override private[testkit] val Policies = SnapshotStorage.SnapshotPolicies

  override def failNextNPersisted(persistenceId: String, n: Int, cause: Throwable): Unit =
    failNextNOpsCond((pid, op) ⇒ pid == persistenceId && op.isInstanceOf[Write], n, cause)

  override def failNextNPersisted(n: Int, cause: Throwable): Unit =
    failNextNOpsCond((_, op) ⇒ op.isInstanceOf[Write], n, cause)

  override def failNextNReads(n: Int, cause: Throwable): Unit =
    failNextNOpsCond((_, op) ⇒ op.isInstanceOf[Read], n, cause)

  override def failNextNReads(persistenceId: String, n: Int, cause: Throwable): Unit =
    failNextNOpsCond((pid, op) ⇒ pid == persistenceId && op.isInstanceOf[Read], n, cause)

  override def failNextNDeletes(n: Int, cause: Throwable): Unit =
    failNextNOpsCond((_, op) ⇒ op.isInstanceOf[Delete], n, cause)

  override def failNextNDeletes(persistenceId: String, n: Int, cause: Throwable): Unit =
    failNextNOpsCond((pid, op) ⇒ pid == persistenceId && op.isInstanceOf[Delete], n, cause)

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

class PersistenceTestKit(override val storage: MessageStorage[_])(implicit val system: ActorSystem)
  extends PersistenceTestKitOps[PersistentRepr, JournalOperation]
  with ExpectOps[PersistentRepr]
  with HasStorage[PersistentRepr, JournalOperation] {
  require(Try(Persistence(system).journalFor(PersistenceTestKitPlugin.PluginId)).isSuccess, "The test persistence plugin is not configured.")

  import PersistenceTestKit._
  import UtilityAssertions._
  import MessageStorage._

  implicit private lazy val ec = system.dispatcher

  private final lazy val settings = Settings(system)

  override private[testkit] val Policies = MessageStorage.JournalPolicies

  override private[testkit] val pollInterval: FiniteDuration = settings.pollInterval

  override private[testkit] val maxTimeout: FiniteDuration = settings.assertTimeout

  def expectPersistedInAnyOrder[A](persistenceId: String, msgs: immutable.Seq[A], max: FiniteDuration): immutable.Seq[A] = {
    val nextInd = nextIndexByPersistenceId.getOrElse(persistenceId, 0)
    val res = awaitAssert({
      val actual = storage.findMany(persistenceId, nextInd, msgs.size)
      actual match {
        case Some(reprs) ⇒
          val ls = reprs.map(reprToAny)
          assert(ls.size == msgs.size && ls.diff(msgs).isEmpty, "Persisted messages do not correspond to the expected ones.")
        case None ⇒ assert(false, "No messages were persisted.")
      }
      actual.get.map(reprToAny)
    }, max = max, interval = pollInterval)

    nextIndexByPersistenceId += (persistenceId -> (nextInd + msgs.size))
    res.asInstanceOf[immutable.Seq[A]]
  }

  def expectPersistedInAnyOrder[A](persistenceId: String, msgs: immutable.Seq[A]): immutable.Seq[A] =
    expectPersistedInAnyOrder(persistenceId, msgs, maxTimeout)

  override def rejectNextNPersisted(persistenceId: String, n: Int, cause: Throwable): Unit =
    rejectNextNOpsCond((pid, op) ⇒ pid == persistenceId && op.isInstanceOf[Write], n, cause)

  override def rejectNextNPersisted(n: Int, cause: Throwable): Unit =
    rejectNextNOpsCond((_, op) ⇒ op.isInstanceOf[Write], n, cause)

  override def rejectNextNReads(n: Int, cause: Throwable): Unit =
    rejectNextNOpsCond((_, op) ⇒ op.isInstanceOf[Read], n, cause)

  override def rejectNextNReads(persistenceId: String, n: Int, cause: Throwable): Unit =
    rejectNextNOpsCond((pid, op) ⇒ pid == persistenceId && op.isInstanceOf[Read], n, cause)

  override def rejectNextNDeletes(n: Int, cause: Throwable): Unit =
    rejectNextNOpsCond((_, op) ⇒ op.isInstanceOf[Delete], n, cause)

  override def rejectNextNDeletes(persistenceId: String, n: Int, cause: Throwable): Unit =
    rejectNextNOpsCond((pid, op) ⇒ pid == persistenceId && op.isInstanceOf[Delete], n, cause)

  override def failNextNPersisted(persistenceId: String, n: Int, cause: Throwable): Unit =
    failNextNOpsCond((pid, op) ⇒ pid == persistenceId && op.isInstanceOf[Write], n, cause)

  override def failNextNPersisted(n: Int, cause: Throwable): Unit =
    failNextNOpsCond((_, op) ⇒ op.isInstanceOf[Write], n, cause)

  override def failNextNReads(n: Int, cause: Throwable): Unit =
    failNextNOpsCond((_, op) ⇒ op.isInstanceOf[Read], n, cause)

  override def failNextNReads(persistenceId: String, n: Int, cause: Throwable): Unit =
    failNextNOpsCond((pid, op) ⇒ pid == persistenceId && op.isInstanceOf[Read], n, cause)

  override def failNextNDeletes(n: Int, cause: Throwable): Unit =
    failNextNOpsCond((_, op) ⇒ op.isInstanceOf[Delete], n, cause)

  override def failNextNDeletes(persistenceId: String, n: Int, cause: Throwable): Unit =
    failNextNOpsCond((pid, op) ⇒ pid == persistenceId && op.isInstanceOf[Delete], n, cause)

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
