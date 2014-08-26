/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.util.control.ControlThrowable

/**
 * INTERNAL API
 *
 * This exception must be thrown from a callback-based stream publisher to
 * signal the end of stream (if the produced stream is not infinite). This is used for example in
 * [[akka.stream.scaladsl.Flow#apply]] (the variant which takes a closure).
 */
private[akka] case object Stop extends ControlThrowable
