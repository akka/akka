/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import akka.persistence.testkit.scaladsl.PolicyOpsTestKit.ExpectedFailure
import akka.persistence.testkit.scaladsl.ProcessingPolicy.DefaultPolicies
import akka.persistence.testkit.scaladsl.RejectSupport.ExpectedRejection

import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration

trait RejectSupport[U] { this: PolicyOpsTestKit[U] with HasStorage[_, U] ⇒

  def rejectNextNOpsCond(cond: (String, U) ⇒ Boolean, n: Int): Unit =
    rejectNextNOpsCond(cond, n, ExpectedRejection)

  def rejectNextNOpsCond(cond: (String, U) ⇒ Boolean, n: Int, cause: Throwable): Unit = {
    val current = storage.currentPolicy
    val pol = new Policies.RejectNextNCond(n, cause, cond, withPolicy(current))
    withPolicy(pol)
  }

  def rejectNextNOps(n: Int): Unit =
    rejectNextNOps(n, ExpectedRejection)

  def rejectNextNOps(n: Int, cause: Throwable): Unit = {
    val current = storage.currentPolicy
    val pol = new Policies.RejectNextN(n, cause, withPolicy(current))
    withPolicy(pol)
  }

}

object RejectSupport {

  object ExpectedRejection extends Throwable

}

trait PolicyOpsTestKit[P] extends { this: HasStorage[_, P] ⇒

  private[testkit] val Policies: DefaultPolicies[P]

  def failNextNOpsCond(cond: (String, P) ⇒ Boolean, n: Int): Unit =
    failNextNOps(n, ExpectedFailure)

  def failNextNOpsCond(cond: (String, P) ⇒ Boolean, n: Int, cause: Throwable): Unit = {
    val current = storage.currentPolicy
    val pol = new Policies.FailNextNCond(n, cause, cond: (String, P) ⇒ Boolean, withPolicy(current))
    withPolicy(pol)
  }

  def failNextNOps(n: Int): Unit =
    failNextNOps(n, ExpectedFailure)

  def failNextNOps(n: Int, cause: Throwable): Unit = {
    val current = storage.currentPolicy
    val pol = new Policies.FailNextN(n, cause, withPolicy(current))
    withPolicy(pol)
  }

  def withPolicy(policy: Policies.PolicyType): this.type = {
    storage.setPolicy(policy)
    this
  }

}

object PolicyOpsTestKit {

  object ExpectedFailure extends Throwable

}

trait ExpectOps[U] { this: HasStorage[U, _] ⇒
  import UtilityAssertions._

  private[testkit] def pollInterval: FiniteDuration

  private[testkit] def maxTimeout: FiniteDuration

  private[testkit] def reprToAny(repr: U): Any

  def persistedInStorage(persistenceId: String): immutable.Seq[Any] =
    storage.read(persistenceId).getOrElse(List.empty).map(reprToAny)

  def expectNextPersisted[A](persistenceId: String, msg: A): A =
    expectNextPersisted(persistenceId, msg, maxTimeout)

  def expectNextPersisted[A](persistenceId: String, msg: A, max: FiniteDuration): A = {
    val nextInd = nextIndexByPersistenceId.getOrElse(persistenceId, 0)
    val expected = Some(msg)
    val res = awaitAssert({
      val actual = storage.findOneByIndex(persistenceId, nextInd).map(reprToAny)
      assert(actual == expected, s"Failed to persist $msg, got $actual instead")
      actual
    }, max = max, interval = pollInterval)

    nextIndexByPersistenceId += (persistenceId -> (nextInd + 1))
    res.get.asInstanceOf[A]
  }

  def expectNothingPersisted(persistenceId: String): Unit =
    expectNothingPersisted(persistenceId, maxTimeout)

  def expectNothingPersisted(persistenceId: String, max: FiniteDuration): Unit = {
    val nextInd = nextIndexByPersistenceId.getOrElse(persistenceId, 0)
    assertForDuration({
      val actual = storage.findOneByIndex(persistenceId, nextInd).map(reprToAny)
      val res = actual.isEmpty
      assert(res, s"Found persisted message $actual, but expected None instead")
    }, max = max, interval = pollInterval)
  }

  def persistForRecovery(persistenceId: String, msgs: immutable.Seq[Any]): Unit = {
    storage.addAny(persistenceId, msgs)
    nextIndexByPersistenceId += persistenceId -> (nextIndexByPersistenceId.getOrElse(persistenceId, 0) + msgs.size)
  }

  def expectPersistedInOrder[A](persistenceId: String, msgs: immutable.Seq[A], max: FiniteDuration): immutable.Seq[A] = {
    val nextInd = nextIndexByPersistenceId.getOrElse(persistenceId, 0)
    val res = awaitAssert({
      val actual = storage.findMany(persistenceId, nextInd, msgs.size)
      actual match {
        case Some(reprs) ⇒
          val ls = reprs.map(reprToAny)
          assert(ls.size == msgs.size && ls.zip(msgs).forall(e ⇒ e._1 == e._2), "Persisted messages do not correspond to expected ones")
        case None ⇒ assert(false, "No messages were persisted")
      }
      actual.get.map(reprToAny)
    }, max = max, interval = pollInterval)

    nextIndexByPersistenceId += (persistenceId -> (nextInd + msgs.size))
    res.asInstanceOf[immutable.Seq[A]]
  }

  def expectPersistedInOrder[A](persistenceId: String, msgs: immutable.Seq[A]): immutable.Seq[A] =
    expectPersistedInOrder(persistenceId, msgs, maxTimeout)

}

trait ClearOps { this: HasStorage[_, _] ⇒

  def clearAll(): Unit = {
    storage.clearAll()
    nextIndexByPersistenceId = Map.empty
  }

  def clearByPersistenceId(persistenceId: String): Unit = {
    storage.removeKey(persistenceId)
    nextIndexByPersistenceId -= persistenceId
  }

  def clearAllPreservingSeqNumbers(): Unit = {
    storage.clearAllPreservingSeqNumbers()
    nextIndexByPersistenceId = Map.empty
  }

  def clearByIdPreservingSeqNumbers(persistenceId: String): Unit = {
    storage.removePreservingSeqNumber(persistenceId)
    nextIndexByPersistenceId -= persistenceId
  }

}

trait HasStorage[V, P] {

  protected def storage: TestKitStorage[V, P, _]

  //todo needs to be thread safe (atomic read-increment-write) for parallel tests?
  @volatile
  private[testkit] var nextIndexByPersistenceId: immutable.Map[String, Int] = Map.empty

}
