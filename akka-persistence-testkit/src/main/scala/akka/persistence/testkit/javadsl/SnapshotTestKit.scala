/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.javadsl

import akka.actor.ActorSystem
import akka.persistence.testkit.scaladsl.{ SnapshotTestKit ⇒ ScalaTestKit }
import akka.persistence.testkit.SnapshotStorage
import akka.util.JavaDurationConverters._

import scala.collection.JavaConverters._
import java.time.Duration
import java.util.{ List ⇒ JList }
import java.util.{ function ⇒ jf }

import akka.japi.Pair
import akka.persistence.testkit.ProcessingPolicy.ExpectedFailure
import akka.persistence.testkit.SnapshotStorage.SnapshotMeta
import akka.testkit.javadsl.CachingPartialFunction

class SnapshotTestKit(system: ActorSystem) {
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
   * Persist `elems` snapshots with metadata into storage in order.
   */
  def persistForRecovery(persistenceId: String, elems: JList[Pair[SnapshotMeta, Any]]): Unit =
    scalaTestkit.persistForRecovery(persistenceId, elems.map(_.toScala))

  /**
   * Retrieve all snapshots and their metadata saved in storage by persistence id.
   */
  def persistedInStorage(persistenceId: String): JList[Pair[SnapshotMeta, Any]] =
    scalaTestkit.persistedInStorage(persistenceId).map(p ⇒ Pair(p._1, p._2)).asJava

  /**
   * Clear all data from storage.
   */
  def clearAll(): Unit = scalaTestkit.clearAll()

  /**
   * Clear all data from storage for particular persistence id.
   */
  def clearByPersistenceId(persistenceId: String): Unit = scalaTestkit.clearByPersistenceId(persistenceId)

  /**
   * Fail `n` following journal events depending on the condition `cond`.
   * Failure triggers, when `cond` returns true, .
   * Fails events with default `ExpectedFailure` exception.
   */
  def failNextNOpsCond(cond: jf.BiFunction[String, SnapshotStorage.SnapshotOperation, Boolean], n: Int): Unit =
    failNextNOpsCond(cond, n, ExpectedFailure)

  /**
   * Fail `n` following journal events depending on the condition `cond`.
   * Failure triggers, when `cond` returns true, .
   * Fails events with the `cause` exception.
   */
  def failNextNOpsCond(cond: jf.BiFunction[String, SnapshotStorage.SnapshotOperation, Boolean], n: Int, cause: Throwable): Unit =
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
  def withPolicy(policy: SnapshotStorage.SnapshotPolicies.PolicyType): this.type = {
    scalaTestkit.withPolicy(policy)
    this
  }

  /**
   * Returns default policy if it was changed by [[SnapshotTestKit.withPolicy()]].
   */
  def returnDefaultPolicy(): Unit = scalaTestkit.returnDefaultPolicy()

}
