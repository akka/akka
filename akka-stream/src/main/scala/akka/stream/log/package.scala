package akka.stream

import akka.stream.scaladsl.Flow

/**
 * Implicit inrichment for flow logging.
 *
 * {{{
 * import akka.stream.log
 * Flow(List(1, 2, 3)).log().consume(materializer)
 * }}}
 *
 * @see [[akka.stream.log.Log]]
 */
package object log {
  implicit class LogDsl[T](val flow: Flow[T]) extends AnyVal {
    def log(): Flow[T] = flow.transform(Log())
  }
}