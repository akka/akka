/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

trait ProcessingPolicy[U] {

  import ProcessingPolicy._

  def tryProcess(processingUnit: U): ProcessingResult

}

object ProcessingPolicy {

  trait PassAll[U] extends ProcessingPolicy[U] {

    override def tryProcess(processingUnit: U): ProcessingResult = ProcessingSuccess

  }

  abstract class RejectNextN[U](numberToReject: Int, rejectionException: Throwable) extends ProcessingPolicy[U] {

    private var rejected = 0

    private val rejection = Reject(rejectionException)

    override def tryProcess(processingUnit: U): ProcessingResult = {
      if (rejected < numberToReject) {
        rejected += 1
        rejection
      } else {
        ProcessingSuccess
      }
    }
  }

  abstract class FailNextN[U](numberToFail: Int, failureException: Throwable) extends ProcessingPolicy[U] {

    private var failed = 0

    private val failure = StorageFailure(failureException)

    override def tryProcess(batch: U): ProcessingResult = {
      if (failed < numberToFail) {
        failed += 1
        failure
      } else {
        ProcessingSuccess
      }
    }

  }

  trait BasicPolicies[U] {

    object PassAll extends ProcessingPolicy.PassAll[U]

    class FailNextN(_numberToFail: Int, _failureException: Throwable) extends ProcessingPolicy.FailNextN[U](_numberToFail, _failureException)

    class RejectNextN(_numberToReject: Int, _rejectionException: Throwable) extends ProcessingPolicy.RejectNextN[U](_numberToReject, _rejectionException)

  }

  trait ProcessingResult

  object ProcessingSuccess extends ProcessingResult

  trait ProcessingFailure extends ProcessingResult {

    def error: Throwable

  }

  case class Reject(error: Throwable) extends ProcessingFailure

  case class StorageFailure(error: Throwable) extends ProcessingFailure

}

