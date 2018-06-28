/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import akka.actor.{ActorSystem, ExtendedActorSystem, Extension, ExtensionId}
import akka.persistence.testkit.scaladsl.InMemStorageEmulator.JournalOperation
import akka.persistence.testkit.scaladsl.PersistenceTestKit.{ExpectedFailure, ExpectedRejection}
import akka.persistence.testkit.scaladsl.ProcessingPolicy.DefaultPolicies
import com.typesafe.config.Config

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

trait PersistenceTestKit extends PersistentTestKitOps {

  import PersistenceTestKit._
  import UtilityAssertions._

  def system: ActorSystem

  implicit lazy val ec = system.dispatcher

  protected final lazy val storage = InMemStorageExtension(system)
  private final lazy val settings = SettingsExtension(system)

  //todo needs to be thread safe (atomic read-increment-write) for parallel tests?
  private var nextIndexByPersistenceId: immutable.Map[String, Int] = Map.empty

  override def persistedMessages(persistenceId: String): immutable.Seq[Any] =
    storage.read(persistenceId).getOrElse(List.empty).map(_.payload)

  override def expectNextPersisted[A](persistenceId: String, msg: A): A = expectNextPersisted(persistenceId, msg, settings.assertTimeout)

  override def expectNextPersisted[A](persistenceId: String, msg: A, max: FiniteDuration): A = {
    val nextInd = nextIndexByPersistenceId.getOrElse(persistenceId, 0)
    val expected = Some(msg)
    val res = awaitAssert({
      val actual = storage.findOneByIndex(persistenceId, nextInd).map(_.payload)
      assert(actual == expected, s"Failed to persist $msg, got $actual instead")
      actual
    }, max = max, interval = settings.pollInterval)

    nextIndexByPersistenceId += (persistenceId -> (nextInd + 1))
    res.get.asInstanceOf[A]
  }

  override def expectNoMessagePersisted(persistenceId: String): Unit =
    expectNoMessagePersisted(persistenceId, settings.noMessagePersistedTimeout)

  override def expectNoMessagePersisted(persistenceId: String, max: FiniteDuration): Unit = {
    val nextInd = nextIndexByPersistenceId.getOrElse(persistenceId, 0)
    assertCondition({
      val actual = storage.findOneByIndex(persistenceId, nextInd).map(_.payload)
      val res = actual.isEmpty
      assert(res, s"Found persisted message $actual, but expected None instead")
      res
    }, max = max, interval = settings.pollInterval)
  }

  override def persistForRecovery(persistenceId: String, msgs: immutable.Seq[Any]): Unit = {
    storage.addAny(persistenceId, msgs)
    nextIndexByPersistenceId += persistenceId -> (nextIndexByPersistenceId.getOrElse(persistenceId, 0) + msgs.size)
  }

  override def expectPersistedInOrder[A](persistenceId: String, msgs: immutable.Seq[A], max: FiniteDuration): immutable.Seq[A] = {
    val nextInd = nextIndexByPersistenceId.getOrElse(persistenceId, 0)
    val res = awaitAssert({
      val actual = storage.findMany(persistenceId, nextInd, msgs.size)
      actual match {
        case Some(reprs) ⇒
          val ls = reprs.map(_.payload)
          assert(ls.size == msgs.size && ls.zip(msgs).forall(e ⇒ e._1 == e._2), "Persisted messages do not correspond to expected ones")
        case None ⇒ assert(false, "No messages were persisted")
      }
      actual.get.map(_.payload)
    }, max = max, interval = settings.pollInterval)

    nextIndexByPersistenceId += (persistenceId -> (nextInd + msgs.size))
    res.asInstanceOf[immutable.Seq[A]]
  }

  override def expectPersistedInOrder[A](persistenceId: String, msgs: immutable.Seq[A]): immutable.Seq[A] =
    expectPersistedInOrder(persistenceId, msgs, settings.assertTimeout)

  override def expectPersistedInAnyOrder[A](persistenceId: String, msgs: immutable.Seq[A], max: FiniteDuration): immutable.Seq[A] = {

    val nextInd = nextIndexByPersistenceId.getOrElse(persistenceId, 0)
    val res = awaitAssert({
      val actual = storage.findMany(persistenceId, nextInd, msgs.size)
      actual match {
        case Some(reprs) ⇒
          val ls = reprs.map(_.payload)
          assert(ls.size == msgs.size && ls.diff(msgs).isEmpty, "Persisted messages do not correspond to expected ones")
        case None ⇒ assert(false, "No messages were persisted")
      }
      actual.get.map(_.payload)
    }, max = max, interval = settings.pollInterval)

    nextIndexByPersistenceId += (persistenceId -> (nextInd + msgs.size))
    res.asInstanceOf[immutable.Seq[A]]
  }

  override def expectPersistedInAnyOrder[A](persistenceId: String, msgs: immutable.Seq[A]): immutable.Seq[A] =
    expectPersistedInAnyOrder(persistenceId, msgs, settings.assertTimeout)

  override def clearAll(): Unit = storage.clearAll()

  override def clearByPersistenceId(persistenceId: String): Unit = storage.removeKey(persistenceId)

  override def clearAllPreservingSeqNumbers(): Unit = storage.clearAllPreservingSeqNumbers()

  override def clearByPersistenceIdPreservingSeqNumbers(persistenceId: String): Unit = storage.removePreservingSeqNumber(persistenceId)

}

object PersistenceTestKit {

  object SettingsExtension extends ExtensionId[Settings] {

    import Settings._

    override def createExtension(system: ExtendedActorSystem): Settings =
      new Settings(system.settings.config.getConfig(configPath))

  }

  class Settings(config: Config) extends Extension {

    import akka.util.Helpers._

    val assertTimeout: FiniteDuration = config.getMillisDuration("assert-timeout")
    val noMessagePersistedTimeout: FiniteDuration = config.getMillisDuration("assert-no-message-timeout")
    val pollInterval: FiniteDuration = config.getMillisDuration("assert-poll-interval")

  }

  object Settings {
    val configPath = "akka.persistence.testkit"
  }

  object ExpectedFailure extends Throwable

  object ExpectedRejection extends Throwable

}

trait RejectSupport[U] { this: PolicyOpsTestKit[U] =>

  def rejectNextNOpsCond(cond: (String, U) => Boolean, n: Int): Unit = {
    val current = storage.currentPolicy
    val pol = new RejectNextNCond(n, ExpectedRejection, cond, withPolicy(current))
    withPolicy(pol)
  }

  def rejectNextNOps(n: Int): Unit = {
    val current = storage.currentPolicy
    val pol = new RejectNextN(n, ExpectedRejection, withPolicy(current))
    withPolicy(pol)
  }

}


trait PolicyOpsTestKit[U] extends DefaultPolicies[U] {

  protected def storage: PolicyOps[U]

  def failNextNOpsCond(cond: (String, U) => Boolean, n: Int): Unit = {
    val current = storage.currentPolicy
    val pol = new FailNextNCond(n, ExpectedFailure, cond: (String, U) => Boolean, withPolicy(current))
    withPolicy(pol)
  }



  def failNextNOps(n: Int): Unit = {
    val current = storage.currentPolicy
    val pol = new FailNextN(n, ExpectedFailure, withPolicy(current))
    withPolicy(pol)
  }


  def withPolicy(policy: PolicyType): this.type = {
    storage.setPolicy(policy)
    this
  }

}

trait PersistentTestKitOps extends PolicyOpsTestKit[JournalOperation] with RejectSupport[JournalOperation]{
  import InMemStorageEmulator._

  def expectNoMessagePersisted(persistenceId: String): Unit

  def expectNoMessagePersisted(persistenceId: String, max: FiniteDuration): Unit

  def expectNextPersisted[A](persistenceId: String, msg: A): A

  def expectNextPersisted[A](persistenceId: String, msg: A, max: FiniteDuration): A

  def expectPersistedInOrder[A](persistenceId: String, msgs: immutable.Seq[A]): immutable.Seq[A]

  def expectPersistedInOrder[A](persistenceId: String, msgs: immutable.Seq[A], max: FiniteDuration): immutable.Seq[A]

  def expectPersistedInAnyOrder[A](persistenceId: String, msgs: immutable.Seq[A]): immutable.Seq[A]

  def expectPersistedInAnyOrder[A](persistenceId: String, msgs: immutable.Seq[A], max: FiniteDuration): immutable.Seq[A]

  def rejectNextNPersisted(persistenceId: String, n: Int): Unit =
    rejectNextNOpsCond((pid, op) => pid == persistenceId && op.isInstanceOf[Write], n)

  def rejectNextPersisted(persistenceId: String): Unit = rejectNextNPersisted(persistenceId, 1)

  def rejectNextNPersisted(n: Int): Unit =
    rejectNextNOpsCond((_, op) =>  op.isInstanceOf[Write], n)

  def rejectNextPersisted(): Unit = rejectNextNPersisted(1)

  def failNextNPersisted(persistenceId: String, n: Int): Unit =
    failNextNOpsCond((pid, op) => pid == persistenceId && op.isInstanceOf[Write], n)

  def failNextPersisted(persistenceId: String): Unit = failNextNPersisted(persistenceId, 1)

  def failNextNPersisted(n: Int): Unit =
    failNextNOpsCond((_, op) => op.isInstanceOf[Write], n)

  def failNextPersisted(): Unit = failNextNPersisted(1)

  def failNextRead(): Unit = failNextNReads(1)

  def failNextNReads(n: Int): Unit =
    failNextNOpsCond((_, op) => op.isInstanceOf[Read], n)

  def failNextRead(persistenceId: String): Unit = failNextNReads(persistenceId, 1)

  def failNextNReads(persistenceId: String, n: Int): Unit =
    failNextNOpsCond((pid, op) => pid == persistenceId && op.isInstanceOf[Read],n)

  def persistForRecovery(persistenceId: String, msgs: immutable.Seq[Any]): Unit

  def persistedMessages(persistenceId: String): immutable.Seq[Any]

  def clearAll(): Unit

  def clearByPersistenceId(persistenceId: String): Unit

  def clearAllPreservingSeqNumbers(): Unit

  def clearByPersistenceIdPreservingSeqNumbers(persistenceId: String): Unit

}

trait SnapshotTestKitOps extends PolicyOpsTestKit[JournalOperation]{
  import SnapShotStorageEmulator._

  def expectNoSnapshots(persistenceId: String): Unit

  def expectNoSnapshots(persistenceId: String, max: FiniteDuration): Unit

  def expectNextSnapshot[A](persistenceId: String, msg: A): A

  def expectNextSnapshot[A](persistenceId: String, snapshot: A, max: FiniteDuration): A

  def failNextNWrites(persistenceId: String, n: Int): Unit =
    failNextNOpsCond((pid, op) => pid == persistenceId && op.isInstanceOf[Write],n)

  def failNextWrite(persistenceId: String): Unit = failNextNWrites(persistenceId, 1)

  def failNextNWrites(n: Int): Unit =
    failNextNOpsCond((_, op) => op.isInstanceOf[Write], n)

  def failNextWrite(): Unit = failNextNWrites(1)

  def failNextRead(): Unit = failNextNReads(1)

  def failNextNReads(n: Int): Unit =
    failNextNOpsCond((_, op) => op.isInstanceOf[Read], n)

  def failNextRead(persistenceId: String): Unit = failNextNReads(persistenceId, 1)

  def failNextNReads(persistenceId: String, n: Int): Unit =
    failNextNOpsCond((pid, op) => pid == persistenceId && op.isInstanceOf[Read],n)

  def failNextDelete(): Unit = failNextNDeletes(1)

  def failNextNDeletes(n: Int): Unit =
    failNextNOpsCond((_, op) => op.isInstanceOf[Delete], n)

  def failNextDelete(persistenceId: String): Unit = failNextNDeletes(persistenceId, 1)

  def failNextNDeletes(persistenceId: String, n: Int): Unit =
    failNextNOpsCond((pid, op) => pid == persistenceId && op.isInstanceOf[Delete],n)

  def persistSnapshot(persistenceId: String, snapshot: Any): Unit

  def clearAll(): Unit

  def clearByPersistenceId(persistenceId: String): Unit

  def clearAllPreservingSeqNumbers(): Unit

  def clearByPersistenceIdPreservingSeqNumbers(persistenceId: String): Unit

  def persistedSnapshots(persistenceId: String): immutable.Seq[Any]

}

trait UtilityAssertions {

  import scala.concurrent.duration._

  protected def now: FiniteDuration = System.nanoTime.nanos

  def awaitAssert[A](a: ⇒ A, max: FiniteDuration, interval: Duration = 100.millis): A = {
    val stop = now + max

    @tailrec
    def poll(t: Duration): A = {
      // cannot use null-ness of result as signal it failed
      // because Java API and not wanting to return a value will be "return null"
      var failed = false
      val result: A =
        try {
          val aRes = a
          failed = false
          aRes
        } catch {
          case NonFatal(e) ⇒
            failed = true
            if ((now + t) >= stop) throw e
            else null.asInstanceOf[A]
        }

      if (!failed) result
      else {
        Thread.sleep(t.toMillis)
        poll((stop - now) min interval)
      }
    }

    poll(max min interval)
  }

  def assertCondition(a: ⇒ Boolean, max: FiniteDuration, interval: Duration = 100.millis): Unit = {
    val stop = now + max

    @tailrec
    def poll(t: Duration): Unit = {
      // cannot use null-ness of result as signal it failed
      // because Java API and not wanting to return a value will be "return null"
      val result: Boolean = a
      val instantNow = now

      if (result && instantNow < stop) {
        Thread.sleep(t.toMillis)
        poll((stop - now) min interval)
      } else if (!result) {
        throw new AssertionError("Assert condition failed")
      }
    }

    poll(max min interval)
  }

}

object UtilityAssertions extends UtilityAssertions
