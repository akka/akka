/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import akka.Done

import scala.util.control.NoStackTrace
import scala.util.{ Failure, Success, Try }

/**
 * Holds a result of an IO operation.
 *
 * @param count Numeric value depending on context, for example IO operations performed or bytes processed.
 * @param status Status of the result. Can be either [[akka.Done]] or an exception.
 */
final case class IOResult(count: Long, status: Try[Done]) {

  def withCount(value: Long): IOResult = copy(count = value)
  def withStatus(value: Try[Done]): IOResult = copy(status = value)

  /**
   * Java API: Numeric value depending on context, for example IO operations performed or bytes processed.
   */
  def getCount: Long = count

  /**
   * Java API: Indicates whether IO operation completed successfully or not.
   */
  def wasSuccessful: Boolean = status.isSuccess

  /**
   * Java API: If the IO operation resulted in an error, returns the corresponding [[Throwable]]
   * or throws [[UnsupportedOperationException]] otherwise.
   */
  def getError: Throwable = status match {
    case Failure(t) ⇒ t
    case Success(_) ⇒ throw new UnsupportedOperationException("IO operation was successful.")
  }

}

object IOResult {

  /** JAVA API: Creates successful IOResult */
  def createSuccessful(count: Long): IOResult =
    new IOResult(count, Success(Done))

  /** JAVA API: Creates failed IOResult, `count` should be the number of bytes (or other unit, please document in your APIs) processed before failing */
  def createFailed(count: Long, ex: Throwable): IOResult =
    new IOResult(count, Failure(ex))
}

/**
 * This exception signals that a stream has been completed by an onError signal
 * while there was still IO operations in progress.
 */
final case class AbruptIOTerminationException(ioResult: IOResult, cause: Throwable)
  extends RuntimeException("Stream terminated without completing IO operation.", cause) with NoStackTrace
