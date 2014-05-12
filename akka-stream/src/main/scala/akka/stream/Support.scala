/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.util.control.NoStackTrace

/**
 * This exception must be thrown from a callback-based stream producer to
 * signal the end of stream (if the produced stream is not infinite). This is used for example in
 * [[akka.stream.scaladsl.Flow#apply]] (the variant which takes a closure).
 */
case object Stop extends RuntimeException("Stop this flow") with NoStackTrace {
  /**
   * Java API: get the singleton instance
   */
  def getInstance = this
}
