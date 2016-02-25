/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream

import akka.Done
import scala.util.{ Failure, Success, Try }

/**
 * Holds a result of an IO operation.
 *
 * @param count Numeric value depending on context, for example IO operations performed or bytes processed.
 * @param status Status of the result. Can be either [[akka.Done]] or an exception.
 */
final case class IOResult private[stream] (count: Long, status: Try[Done]) {

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
