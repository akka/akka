package akka.persistence.testkit

import scala.collection.immutable

trait ProcessingPolicy {

  def tryProcess(batch: immutable.Seq[Any]): ProcessingResult

}

object ProcessingPolicy {

  object Default extends ProcessingPolicy {
    override def tryProcess(batch: immutable.Seq[Any]): ProcessingResult = ProcessingSuccess
  }

}

trait ProcessingResult

object ProcessingSuccess extends ProcessingResult

trait ProcessingFailure extends ProcessingResult {

  def error: Throwable

}

case class Reject(error: Throwable) extends ProcessingFailure

case class StorageFailure(error: Throwable) extends ProcessingFailure
