/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import akka.persistence.testkit.ProcessingPolicy.DefaultPolicies
import akka.persistence.testkit.{ ExpectedFailure, ExpectedRejection, TestKitStorage }
import akka.testkit.TestKitBase

import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration

private[testkit] trait RejectSupport[U] {
  this: PolicyOpsTestKit[U] with HasStorage[U, _] ⇒

  /**
   * Reject `n` following journal events depending on the condition `cond`.
   * Rejection triggers, when `cond` returns true, .
   * Reject events with default `ExpectedRejection` exception.
   */
  def rejectNextNOpsCond(cond: (String, U) ⇒ Boolean, n: Int): Unit =
    rejectNextNOpsCond(cond, n, ExpectedRejection)

  /**
   * Reject `n` following journal events depending on the condition `cond`.
   * Rejection triggers, when `cond` returns true, .
   * Rejects events with the `cause` exception.
   */
  def rejectNextNOpsCond(cond: (String, U) ⇒ Boolean, n: Int, cause: Throwable): Unit = {
    val current = storage.currentPolicy
    val pol = new Policies.RejectNextNCond(n, cause, cond, withPolicy(current))
    withPolicy(pol)
  }

  /**
   * Reject n following journal events regardless of their type.
   * Rejects events with default `ExpectedRejection` exception.
   */
  def rejectNextNOps(n: Int): Unit =
    rejectNextNOps(n, ExpectedRejection)

  /**
   * Reject `n` following journal events regardless of their type.
   * Rejects events with the `cause` exception.
   */
  def rejectNextNOps(n: Int, cause: Throwable): Unit = {
    val current = storage.currentPolicy
    val pol = new Policies.RejectNextN(n, cause, withPolicy(current))
    withPolicy(pol)
  }

}

private[testkit] trait PolicyOpsTestKit[P] extends {
  this: HasStorage[P, _] ⇒

  private[testkit] val Policies: DefaultPolicies[P]

  /**
   * Fail `n` following journal events depending on the condition `cond`.
   * Failure triggers, when `cond` returns true, .
   * Fails events with default `ExpectedFailure` exception.
   */
  def failNextNOpsCond(cond: (String, P) ⇒ Boolean, n: Int): Unit =
    failNextNOpsCond(cond, n, ExpectedFailure)

  /**
   * Fail `n` following journal events depending on the condition `cond`.
   * Failure triggers, when `cond` returns true, .
   * Fails events with the `cause` exception.
   */
  def failNextNOpsCond(cond: (String, P) ⇒ Boolean, n: Int, cause: Throwable): Unit = {
    val current = storage.currentPolicy
    val pol = new Policies.FailNextNCond(n, cause, cond, withPolicy(current))
    withPolicy(pol)
  }

  /**
   * Fail n following journal events regardless of their type.
   * Fails events with default `ExpectedFailure` exception.
   */
  def failNextNOps(n: Int): Unit =
    failNextNOps(n, ExpectedFailure)

  /**
   * Fail `n` following journal events regardless of their type.
   * Fails events with the `cause` exception.
   */
  def failNextNOps(n: Int, cause: Throwable): Unit = {
    val current = storage.currentPolicy
    val pol = new Policies.FailNextN(n, cause, withPolicy(current))
    withPolicy(pol)
  }

  /**
   * Set new processing policy for journal events.
   * NOTE! Overrides previously invoked `failNext...` or `rejectNext...`
   */
  def withPolicy(policy: Policies.PolicyType): this.type = {
    storage.setPolicy(policy)
    this
  }

  /**
   * Returns default policy if it was changed by [[PolicyOpsTestKit.this.withPolicy()]].
   */
  def returnDefaultPolicy(): Unit = storage.returnDefaultPolicy()

}

private[testkit] trait ExpectOps[U] {
  this: HasStorage[_, U] ⇒

  private[testkit] val probe: TestKitBase

  import probe._
  import akka.testkit._

  private[testkit] def pollInterval: FiniteDuration

  private[testkit] def maxTimeout: FiniteDuration

  private[testkit] def reprToAny(repr: U): Any

  /**
   * Check that next persisted in storage for particular persistence id message was `msg`.
   */
  def expectNextPersisted[A](persistenceId: String, msg: A): A =
    expectNextPersisted(persistenceId, msg, maxTimeout)

  /**
   * Check for `max` time that next persisted in storage for particular persistence id message was `msg`.
   */
  def expectNextPersisted[A](persistenceId: String, msg: A, max: FiniteDuration): A = {
    val nextInd = nextIndex(persistenceId)
    val expected = Some(msg)
    val res = awaitAssert({
      val actual = storage.findOneByIndex(persistenceId, nextInd).map(reprToAny)
      assert(actual == expected, s"Failed to persist $msg, got $actual instead")
      actual
    }, max = max.dilated, interval = pollInterval)

    setIndex(persistenceId, nextInd + 1)
    res.get.asInstanceOf[A]
  }

  /**
   * Check that next persisted in storage for particular persistence id message matches partial function `pf`.
   */
  def expectNextPersistedPF[A](persistenceId: String)(pf: PartialFunction[Any, A]): A =
    expectNextPersistedPF(persistenceId, maxTimeout)(pf)

  /**
   * Check that next persisted in storage for particular persistence id message matches partial function `pf`.
   */
  def expectNextPersistedPF[A](persistenceId: String, hint: String)(pf: PartialFunction[Any, A]): A =
    expectNextPersistedPF(persistenceId, maxTimeout, hint)(pf)

  /**
   * Check for `max` time that next persisted in storage for particular persistence id message matches partial function `pf`.
   */
  def expectNextPersistedPF[A](persistenceId: String, max: FiniteDuration, hint: String = "")(
      pf: PartialFunction[Any, A]): A = {
    val nextInd = nextIndex(persistenceId)
    val res = awaitAssert({
      val actual = storage.findOneByIndex(persistenceId, nextInd).map(reprToAny)
      assert(actual.isDefined, s"Expected: $hint but got unexpected None")
      val a = actual.get
      assert(pf.isDefinedAt(a), s"Expected: $hint but got unexpected $a")
      a
    }, max.dilated, interval = pollInterval)

    setIndex(persistenceId, nextInd + 1)
    pf(res)
  }

  /**
   * Check that nothing was persisted in storage for particular persistence id.
   */
  def expectNothingPersisted(persistenceId: String): Unit =
    expectNothingPersisted(persistenceId, maxTimeout)

  /**
   * Check for `max` time that nothing was persisted in storage for particular persistence id.
   */
  def expectNothingPersisted(persistenceId: String, max: FiniteDuration): Unit = {
    val nextInd = nextIndex(persistenceId)
    assertForDuration({
      val actual = storage.findOneByIndex(persistenceId, nextInd).map(reprToAny)
      val res = actual.isEmpty
      assert(res, s"Found persisted message $actual, but expected None instead")
    }, max = max.dilated, interval = pollInterval)
  }

  /**
   * Check for `max` time that `msgs` messages have been persisted in the storage in order.
   */
  def expectPersistedInOrder[A](
      persistenceId: String,
      msgs: immutable.Seq[A],
      max: FiniteDuration): immutable.Seq[A] = {
    val nextInd = nextIndex(persistenceId)
    val res = awaitAssert(
      {
        val actual = storage.findMany(persistenceId, nextInd, msgs.size)
        actual match {
          case Some(reprs) ⇒
            val ls = reprs.map(reprToAny)
            assert(
              ls.size == msgs.size && ls.zip(msgs).forall(e ⇒ e._1 == e._2),
              "Persisted messages do not correspond to expected ones")
          case None ⇒ assert(false, "No messages were persisted")
        }
        actual.get.map(reprToAny)
      },
      max = max.dilated,
      interval = pollInterval)

    setIndex(persistenceId, nextInd + msgs.size)
    res.asInstanceOf[immutable.Seq[A]]
  }

  /**
   * Check that `msgs` messages have been persisted in the storage in order.
   */
  def expectPersistedInOrder[A](persistenceId: String, msgs: immutable.Seq[A]): immutable.Seq[A] =
    expectPersistedInOrder(persistenceId, msgs, maxTimeout)

  /**
   * Check for `max` time that `msgs` messages have been persisted in the storage regardless of order.
   */
  def expectPersistedInAnyOrder[A](
      persistenceId: String,
      msgs: immutable.Seq[A],
      max: FiniteDuration): immutable.Seq[A] = {
    val nextInd = nextIndex(persistenceId)
    val res = probe.awaitAssert(
      {
        val actual = storage.findMany(persistenceId, nextInd, msgs.size)
        actual match {
          case Some(reprs) ⇒
            val ls = reprs.map(reprToAny)
            assert(
              ls.size == msgs.size && ls.diff(msgs).isEmpty,
              "Persisted messages do not correspond to the expected ones.")
          case None ⇒ assert(false, "No messages were persisted.")
        }
        actual.get.map(reprToAny)
      },
      max = max.dilated,
      interval = pollInterval)

    setIndex(persistenceId, nextInd + msgs.size)
    res.asInstanceOf[immutable.Seq[A]]
  }

  /**
   * Check that `msgs` messages have been persisted in the storage regardless of order.
   */
  def expectPersistedInAnyOrder[A](persistenceId: String, msgs: immutable.Seq[A]): immutable.Seq[A] =
    expectPersistedInAnyOrder(persistenceId, msgs, maxTimeout)

}

private[testkit] trait ClearOps {
  this: HasStorage[_, _] ⇒

  /**
   * Clear all data from storage.
   *
   * NOTE! Also clears sequence numbers in storage!
   *
   * @see [[ClearPreservingSeqNums.clearAllPreservingSeqNumbers()]]
   */
  def clearAll(): Unit = {
    storage.clearAll()
    clearIndexStorage()
  }

  /**
   * Clear all data from storage for particular persistence id.
   *
   * NOTE! Also clears sequence number in storage!
   *
   * @see [[ClearPreservingSeqNums.clearByIdPreservingSeqNumbers()]]
   */
  def clearByPersistenceId(persistenceId: String): Unit = {
    storage.removeKey(persistenceId)
    removeLastIndex(persistenceId)
  }

}

private[testkit] trait ClearPreservingSeqNums {
  this: HasStorage[_, _] ⇒

  /**
   * Clear all data in storage preserving sequence numbers.
   *
   * @see [[ClearOps.clearAll()]]
   */
  def clearAllPreservingSeqNumbers(): Unit = {
    storage.clearAllPreservingSeqNumbers()
    clearIndexStorage()
  }

  /**
   * Clear all data in storage for particular persistence id preserving sequence numbers.
   *
   * @see [[ClearOps.clearByPersistenceId()]]
   */
  def clearByIdPreservingSeqNumbers(persistenceId: String): Unit = {
    storage.removePreservingSeqNumber(persistenceId)
    removeLastIndex(persistenceId)
  }

}

/**
 * Abstract persistent storage for tests.
 * Has additional methods for keeping track of the indexes of last elements persisted in the storage during test.
 */
private[testkit] trait HasStorage[P, R] {

  protected def storage: TestKitStorage[P, R]

  //todo needs to be thread safe (atomic read-increment-write) for parallel tests. Do we need parallel tests support?
  @volatile
  private var nextIndexByPersistenceId: immutable.Map[String, Int] = Map.empty

  private[testkit] def removeLastIndex(persistenceId: String): Unit =
    nextIndexByPersistenceId -= persistenceId

  private[testkit] def clearIndexStorage(): Unit =
    nextIndexByPersistenceId = Map.empty

  private[testkit] def nextIndex(persistenceId: String): Int =
    nextIndexByPersistenceId.getOrElse(persistenceId, 0)

  private[testkit] def setIndex(persistenceId: String, index: Int): Unit =
    nextIndexByPersistenceId += persistenceId -> index

  private[testkit] def addToIndex(persistenceId: String, add: Int): Unit = {
    val nextInd = nextIndexByPersistenceId.getOrElse(persistenceId, 0)
    nextIndexByPersistenceId += (persistenceId -> (nextInd + add))
  }

}
