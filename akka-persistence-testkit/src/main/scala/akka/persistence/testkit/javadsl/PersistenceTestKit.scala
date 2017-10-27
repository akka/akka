/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.javadsl

import akka.actor.ActorSystem
import akka.persistence.testkit.scaladsl.{ PersistenceTestKit ⇒ ScalaTestKit }
import akka.util.JavaDurationConverters._

import scala.collection.JavaConverters._
import java.time.Duration
import java.util.{ List ⇒ JList }
import java.util.{ function ⇒ jf }

import akka.persistence.testkit.MessageStorage
import akka.persistence.testkit.ProcessingPolicy.{ ExpectedFailure, ExpectedRejection }
import akka.testkit.javadsl.CachingPartialFunction

class PersistenceTestKit(system: ActorSystem) {
  import akka.persistence.testkit.Utils.JavaCollectionConversions._
  import akka.persistence.testkit.Utils.JavaFuncConversions._

  private val scalaTestkit = new ScalaTestKit()(system)

  /**
   * Check that nothing has been saved in the storage.
   */
  def expectNothingPersisted(persistenceId: String): Unit = scalaTestkit.expectNothingPersisted(persistenceId)

  /**
   * Check for `max` time that nothing has been saved in the storage.
   */
  def expectNothingPersisted(persistenceId: String, max: Duration): Unit = scalaTestkit.expectNothingPersisted(persistenceId, max.asScala)

  /**
   * Check that `msg` message has been saved in the storage.
   */
  def expectNextPersisted[A](persistenceId: String, msg: A): A = scalaTestkit.expectNextPersisted(persistenceId, msg)

  /**
   * Check for `max` time that `msg` message has been saved in the storage.
   */
  def expectNextPersisted[A](persistenceId: String, msg: A, max: Duration): A = scalaTestkit.expectNextPersisted(persistenceId, msg, max.asScala)

  /**
   * Check that next persisted in storage for particular persistence id message matches partial function `pf`.
   */
  def expectNextPersistedPF[A](persistenceId: String, pf: jf.Function[Any, A]): A =
    expectNextPersistedPF(persistenceId, "", pf)

  /**
   * Check that next persisted in storage for particular persistence id message matches partial function `pf`.
   */
  def expectNextPersistedPF[A](persistenceId: String, hint: String, pf: jf.Function[Any, A]): A =
    scalaTestkit.expectNextPersistedPF(persistenceId, hint)(new CachingPartialFunction[Any, A] {
      @throws(classOf[Exception])
      override def `match`(x: Any): A = pf.apply(x)
    })

  /**
   * Check for `max` time that next persisted in storage for particular persistence id message matches partial function `pf`.
   */
  def expectNextPersistedPF[A](persistenceId: String, max: Duration, hint: String, pf: jf.Function[Any, A]): A =
    scalaTestkit.expectNextPersistedPF(persistenceId, max.asScala, hint)(new CachingPartialFunction[Any, A] {
      @throws(classOf[Exception])
      override def `match`(x: Any): A = pf.apply(x)
    })

  /**
   * Check for `max` time that next persisted in storage for particular persistence id message matches partial function `pf`.
   */
  def expectNextPersistedPF[A](persistenceId: String, max: Duration, pf: jf.Function[Any, A]): A =
    expectNextPersistedPF(persistenceId, max, "", pf)

  /**
   * Fail next `n` persisted messages with the `cause` exception for particular persistence id.
   */
  def failNextNPersisted(persistenceId: String, n: Int, cause: Throwable): Unit = scalaTestkit.failNextNPersisted(persistenceId, n, cause)

  /**
   * Fail next `n` persisted messages for particular persistence id.
   */
  def failNextNPersisted(persistenceId: String, n: Int): Unit = failNextNPersisted(persistenceId, n, ExpectedFailure)

  /**
   * Fail next `n` persisted messages with the `cause` exception for any persistence id.
   */
  def failNextNPersisted(n: Int, cause: Throwable): Unit = scalaTestkit.failNextNPersisted(n, cause)

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
  def failNextNReads(n: Int, cause: Throwable): Unit = scalaTestkit.failNextNReads(n, cause)

  /**
   * Fail next n read from storage (recovery) attempts with default exception for any persistence id.
   */
  def failNextNReads(n: Int): Unit = failNextNReads(n, ExpectedFailure)

  /**
   * Fail next n read from storage (recovery) attempts with `cause` exception for particular persistence id.
   */
  def failNextNReads(persistenceId: String, n: Int, cause: Throwable): Unit = scalaTestkit.failNextNReads(persistenceId, n, cause)

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
  def failNextNDeletes(n: Int, cause: Throwable): Unit = scalaTestkit.failNextNDeletes(n, cause)

  /**
   * Fail next n delete from storage attempts with default exception for any persistence id.
   */
  def failNextNDeletes(n: Int): Unit = failNextNDeletes(n, ExpectedFailure)

  /**
   * Fail next n delete from storage attempts with `cause` exception for particular persistence id.
   */
  def failNextNDeletes(persistenceId: String, n: Int, cause: Throwable): Unit =
    scalaTestkit.failNextNDeletes(persistenceId, n, cause)

  /**
   * Fail next n delete from storage attempts with default exception for particular persistence id.
   */
  def failNextNDeletes(persistenceId: String, n: Int): Unit = failNextNDeletes(persistenceId, n, ExpectedFailure)

  /**
   * Check that `msgs` messages have been persisted in the storage in order.
   */
  def expectPersistedInOrder[A](persistenceId: String, msgs: JList[A]): JList[A] =
    scalaTestkit.expectPersistedInOrder(persistenceId, msgs).asJava

  /**
   * Check for `max` time that `msgs` messages have been persisted in the storage in order.
   */
  def expectPersistedInOrder[A](persistenceId: String, msgs: JList[A], max: Duration): JList[A] =
    scalaTestkit.expectPersistedInOrder(persistenceId, msgs, max.asScala).asJava

  /**
   * Check that `msgs` messages have been persisted in the storage regardless of order.
   */
  def expectPersistedInAnyOrder[A](persistenceId: String, msgs: JList[A]): JList[A] =
    scalaTestkit.expectPersistedInAnyOrder(persistenceId, msgs).asJava

  /**
   * Check for `max` time that `msgs` messages have been persisted in the storage regardless of order.
   */
  def expectPersistedInAnyOrder[A](persistenceId: String, msgs: JList[A], max: Duration): JList[A] =
    scalaTestkit.expectPersistedInAnyOrder(persistenceId, msgs, max.asScala).asJava

  /**
   * Reject next n save in storage operations for particular persistence id with `cause` exception.
   */
  def rejectNextNPersisted(persistenceId: String, n: Int, cause: Throwable): Unit = scalaTestkit.rejectNextNPersisted(persistenceId, n, cause)

  /**
   * Reject next n save in storage operations for particular persistence id with default exception.
   */
  def rejectNextNPersisted(persistenceId: String, n: Int): Unit = rejectNextNPersisted(persistenceId, n, ExpectedRejection)

  /**
   * Reject next n save in storage operations for any persistence id with default exception.
   */
  def rejectNextNPersisted(n: Int): Unit = rejectNextNPersisted(n, ExpectedRejection)

  /**
   * Reject next n save in storage operations for any persistence id with `cause` exception.
   */
  def rejectNextNPersisted(n: Int, cause: Throwable): Unit = scalaTestkit.rejectNextNPersisted(n, cause)

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
  def rejectNextNReads(n: Int, cause: Throwable): Unit = scalaTestkit.rejectNextNReads(n, cause)

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
  def rejectNextNReads(persistenceId: String, n: Int, cause: Throwable): Unit = scalaTestkit.rejectNextNReads(persistenceId, n, cause)

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
  def rejectNextNDeletes(n: Int, cause: Throwable): Unit = scalaTestkit.rejectNextNDeletes(n, cause)

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
  def rejectNextNDeletes(persistenceId: String, n: Int, cause: Throwable): Unit = scalaTestkit.rejectNextNDeletes(persistenceId, n, cause)

  /**
   * Reject `n` following journal events depending on the condition `cond`.
   * Rejection triggers, when `cond` returns true, .
   * Reject events with default `ExpectedRejection` exception.
   */
  def rejectNextNOpsCond(cond: jf.BiFunction[String, MessageStorage.JournalOperation, Boolean], n: Int): Unit =
    rejectNextNOpsCond(cond, n, ExpectedRejection)

  /**
   * Reject `n` following journal events depending on the condition `cond`.
   * Rejection triggers, when `cond` returns true, .
   * Rejects events with the `cause` exception.
   */
  def rejectNextNOpsCond(cond: jf.BiFunction[String, MessageStorage.JournalOperation, Boolean], n: Int, cause: Throwable): Unit =
    scalaTestkit.rejectNextNOpsCond(cond, n, cause)

  /**
   * Reject n following journal events regardless of their type.
   * Rejects events with default `ExpectedRejection` exception.
   */
  def rejectNextNOps(n: Int): Unit = rejectNextNOps(n, ExpectedRejection)

  /**
   * Reject `n` following journal events regardless of their type.
   * Rejects events with the `cause` exception.
   */
  def rejectNextNOps(n: Int, cause: Throwable): Unit = scalaTestkit.rejectNextNOps(n, cause)

  /**
   * Persist `elems` messages into storage in order.
   */
  def persistForRecovery(persistenceId: String, elems: JList[Any]): Unit = scalaTestkit.persistForRecovery(persistenceId, elems)

  /**
   * Retrieve all messages saved in storage by persistence id.
   */
  def persistedInStorage(persistenceId: String): JList[Any] = scalaTestkit.persistedInStorage(persistenceId).asJava

  /**
   * Clear all data from storage.
   *
   * NOTE! Also clears sequence numbers in storage!
   *
   * @see [[PersistenceTestKit.clearAllPreservingSeqNumbers()]]
   */
  def clearAll(): Unit = scalaTestkit.clearAll()

  /**
   * Clear all data from storage for particular persistence id.
   *
   * NOTE! Also clears sequence number in storage!
   *
   * @see [[PersistenceTestKit.clearByIdPreservingSeqNumbers()]]
   */
  def clearByPersistenceId(persistenceId: String): Unit = scalaTestkit.clearByPersistenceId(persistenceId)

  /**
   * Clear all data in storage preserving sequence numbers.
   *
   * @see [[PersistenceTestKit.clearAll()]]
   */
  def clearAllPreservingSeqNumbers(): Unit = scalaTestkit.clearAllPreservingSeqNumbers()

  /**
   * Clear all data in storage for particular persistence id preserving sequence numbers.
   *
   * @see [[PersistenceTestKit.clearByPersistenceId()]]
   */
  def clearByIdPreservingSeqNumbers(persistenceId: String): Unit = scalaTestkit.clearByIdPreservingSeqNumbers(persistenceId)

  /**
   * Fail `n` following journal events depending on the condition `cond`.
   * Failure triggers, when `cond` returns true, .
   * Fails events with default `ExpectedFailure` exception.
   */
  def failNextNOpsCond(cond: jf.BiFunction[String, MessageStorage.JournalOperation, Boolean], n: Int): Unit =
    failNextNOpsCond(cond, n, ExpectedFailure)

  /**
   * Fail `n` following journal events depending on the condition `cond`.
   * Failure triggers, when `cond` returns true, .
   * Fails events with the `cause` exception.
   */
  def failNextNOpsCond(cond: jf.BiFunction[String, MessageStorage.JournalOperation, Boolean], n: Int, cause: Throwable): Unit =
    scalaTestkit.failNextNOpsCond(cond, n, cause)

  /**
   * Fail n following journal events regardless of their type.
   * Fails events with default `ExpectedFailure` exception.
   */
  def failNextNOps(n: Int): Unit =
    failNextNOps(n, ExpectedFailure)

  /**
   * Fail `n` following journal events depending on the condition `cond`.
   * Failure triggers, when `cond` returns true, .
   * Fails events with the `cause` exception.
   */
  def failNextNOps(n: Int, cause: Throwable): Unit = scalaTestkit.failNextNOps(n, cause)

  /**
   * Set new processing policy for journal events.
   * NOTE! Overrides previously invoked `failNext...` or `rejectNext...`
   */
  def withPolicy(policy: MessageStorage.JournalPolicies.PolicyType): this.type = {
    scalaTestkit.withPolicy(policy)
    this
  }

  /**
   * Returns default policy if it was changed by [[PersistenceTestKit.withPolicy()]].
   */
  def returnDefaultPolicy(): Unit = scalaTestkit.returnDefaultPolicy()

}
