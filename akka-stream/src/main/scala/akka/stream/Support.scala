/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.util.control.NoStackTrace

/**
 * This exception can be thrown from a callback-based stream producer to
 * signal the end of stream.
 */
case object Stop extends RuntimeException("Stop this flow") with NoStackTrace