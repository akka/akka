/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import akka.event.LoggingAdapter

/**
 * SPI intended only to be extended by custom [[Materializer]] implementations,
 * that also want to provide operators they materialize with specialized [[akka.event.LoggingAdapter]] instances.
 */
trait MaterializerLoggingProvider { this: Materializer ⇒

  def makeLogger(logSource: Class[_]): LoggingAdapter

}
