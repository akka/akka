package akka.persistence.testkit




trait RejectionPolicy {

  def tryProcess(msg: Any): ProcessingResult

}

trait ProcessingResult

object ProcessingSuccess extends ProcessingResult

trait ProcessingFailure extends ProcessingResult{

  def error: Throwable

}

case class Reject(error: Throwable) extends ProcessingFailure

case class StorageFailure(error: Throwable) extends ProcessingFailure
