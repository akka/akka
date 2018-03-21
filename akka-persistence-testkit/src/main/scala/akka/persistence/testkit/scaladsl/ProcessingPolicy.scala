package akka.persistence.testkit.scaladsl

import scala.collection.immutable

trait ProcessingPolicy {

  import ProcessingPolicy._

  def tryProcess(batch: immutable.Seq[Any]): ProcessingResult

}

object ProcessingPolicy {

  object PassAll extends ProcessingPolicy {
    override def tryProcess(batch: immutable.Seq[Any]): ProcessingResult = ProcessingSuccess
  }

  class RejectNextN(numberToReject: Int, rejectionException: Throwable) extends ProcessingPolicy {

    private var rejected = 0

    private val rejection = Reject(rejectionException)

    override def tryProcess(batch: immutable.Seq[Any]): ProcessingResult = {
      if (rejected < numberToReject) {
        rejected += 1
        rejection
      } else {
        ProcessingSuccess
      }
    }
  }

  class FailNextN(numberToFail: Int, failureException: Throwable) extends ProcessingPolicy {

    private var failed = 0

    private val failure = StorageFailure(failureException)

    override def tryProcess(batch: immutable.Seq[Any]): ProcessingResult = {
      if (failed < numberToFail) {
        failed += 1
        failure
      } else {
        ProcessingSuccess
      }
    }

  }

  trait ProcessingResult

  object ProcessingSuccess extends ProcessingResult

  trait ProcessingFailure extends ProcessingResult {

    def error: Throwable

  }

  case class Reject(error: Throwable) extends ProcessingFailure

  case class StorageFailure(error: Throwable) extends ProcessingFailure

}

