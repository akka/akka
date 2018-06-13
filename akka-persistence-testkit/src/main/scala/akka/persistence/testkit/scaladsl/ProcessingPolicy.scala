/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

trait ProcessingPolicy[U] {

  import ProcessingPolicy._

  def tryProcess(persistenceId: String, processingUnit: U): ProcessingResult

}

object ProcessingPolicy {

  abstract class ReturnAfterNextNCond[U](returnOnTrigger: ⇒ ProcessingResult, returnNonTrigger: ⇒ ProcessingResult, cond: (String, U) ⇒ Boolean) extends ProcessingPolicy[U] {

    override def tryProcess(persistenceId: String, processingUnit: U): ProcessingResult = {
      if (cond(persistenceId, processingUnit)) {
        returnOnTrigger
      } else {
        returnNonTrigger
      }
    }

  }

  abstract class CountNextNCond[U](numberToReject: Int, returnOnTrigger: ⇒ ProcessingResult, returnNonTrigger: ⇒ ProcessingResult, cond: (String, U) ⇒ Boolean, onLimitExceed: ⇒ Unit) extends ReturnAfterNextNCond[U](returnOnTrigger, returnNonTrigger, new Function2[String, U, Boolean] {

    var counter = 0

    override def apply(persistenceId: String, v1: U): Boolean = {
      val intRes = cond(persistenceId, v1)
      if (intRes && counter < numberToReject) {
        counter += 1
        if (counter == numberToReject) onLimitExceed
        intRes
      } else {
        false
      }
    }
  })

  abstract class RejectNextN[U](numberToReject: Int, rejectionException: Throwable, onLimitExceed: ⇒ Unit) extends CountNextNCond[U](numberToReject, Reject(rejectionException), ProcessingSuccess, (_, _) ⇒ true, onLimitExceed)

  abstract class FailNextN[U](numberToFail: Int, failureException: Throwable, onLimitExceed: ⇒ Unit) extends CountNextNCond[U](numberToFail, StorageFailure(failureException), ProcessingSuccess, (_, _) ⇒ true, onLimitExceed)

  trait BasicPolicies[U] {

    object PassAll extends ProcessingPolicy[U] {

      override def tryProcess(persistenceId: String, processingUnit: U): ProcessingResult = ProcessingSuccess

    }

    class RejectNextNCond(_numberToFail: Int, _failureException: Throwable, cond: (String, U) ⇒ Boolean, onLimitExceed: ⇒ Unit = ()) extends ProcessingPolicy.CountNextNCond[U](_numberToFail, Reject(_failureException), ProcessingSuccess, cond, onLimitExceed)

    class FailNextNCond(_numberToFail: Int, _failureException: Throwable, cond: (String, U) ⇒ Boolean, onLimitExceed: ⇒ Unit = ()) extends ProcessingPolicy.CountNextNCond[U](_numberToFail, StorageFailure(_failureException), ProcessingSuccess, cond, onLimitExceed)

    class FailNextN(_numberToFail: Int, _failureException: Throwable, onLimitExceed: ⇒ Unit = ()) extends ProcessingPolicy.FailNextN[U](_numberToFail, _failureException, onLimitExceed)

    class RejectNextN(_numberToReject: Int, _rejectionException: Throwable, onLimitExceed: ⇒ Unit = ()) extends ProcessingPolicy.RejectNextN[U](_numberToReject, _rejectionException, onLimitExceed)

  }

  trait ProcessingResult

  object ProcessingSuccess extends ProcessingResult

  trait ProcessingFailure extends ProcessingResult {

    def error: Throwable

  }

  case class Reject(error: Throwable) extends ProcessingFailure

  case class StorageFailure(error: Throwable) extends ProcessingFailure

}

