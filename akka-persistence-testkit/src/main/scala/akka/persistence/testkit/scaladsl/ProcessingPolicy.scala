/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

trait ProcessingPolicy[U] {

  import ProcessingPolicy._

  def tryProcess(persistenceId: String, processingUnit: U): ProcessingResult

}

object ProcessingPolicy {

  trait DefaultPolicies[U] {

    type PolicyType = ProcessingPolicy[U]

    case object PassAll extends PolicyType {

      override def tryProcess(persistenceId: String, processingUnit: U): ProcessingResult = ProcessingSuccess

    }

    class RejectNextNCond(_numberToFail: Int, _failureException: Throwable, cond: (String, U) ⇒ Boolean, onLimitExceed: ⇒ Unit = ()) extends CountNextNCond(_numberToFail, Reject(_failureException), ProcessingSuccess, cond, onLimitExceed)

    class FailNextNCond(_numberToFail: Int, _failureException: Throwable, cond: (String, U) ⇒ Boolean, onLimitExceed: ⇒ Unit = ()) extends CountNextNCond(_numberToFail, StorageFailure(_failureException), ProcessingSuccess, cond, onLimitExceed)

    class FailNextN(_numberToFail: Int, _failureException: Throwable, onLimitExceed: ⇒ Unit = ()) extends CountNextNCond(_numberToFail, StorageFailure(_failureException), ProcessingSuccess, (_, _) ⇒ true, onLimitExceed)

    class RejectNextN(_numberToReject: Int, _rejectionException: Throwable, onLimitExceed: ⇒ Unit = ()) extends CountNextNCond(_numberToReject, Reject(_rejectionException), ProcessingSuccess, (_, _) ⇒ true, onLimitExceed)

    class ReturnAfterNextNCond(returnOnTrigger: ⇒ ProcessingResult, returnNonTrigger: ⇒ ProcessingResult, cond: (String, U) ⇒ Boolean) extends PolicyType {

      override def tryProcess(persistenceId: String, processingUnit: U): ProcessingResult = {
        if (cond(persistenceId, processingUnit)) {
          returnOnTrigger
        } else {
          returnNonTrigger
        }
      }

    }

    class CountNextNCond(numberToCount: Int, returnOnTrigger: ⇒ ProcessingResult, returnNonTrigger: ⇒ ProcessingResult, cond: (String, U) ⇒ Boolean, onLimitExceed: ⇒ Unit) extends ReturnAfterNextNCond(returnOnTrigger, returnNonTrigger, new Function2[String, U, Boolean] {

      var counter = 0

      override def apply(persistenceId: String, v1: U): Boolean = {
        val intRes = cond(persistenceId, v1)
        if (intRes && counter < numberToCount) {
          counter += 1
          if (counter == numberToCount) onLimitExceed
          intRes
        } else {
          false
        }
      }
    })

  }

  trait ProcessingResult

  object ProcessingSuccess extends ProcessingResult

  trait ProcessingFailure extends ProcessingResult {

    def error: Throwable

  }

  case class Reject(error: Throwable) extends ProcessingFailure

  case class StorageFailure(error: Throwable) extends ProcessingFailure

}

