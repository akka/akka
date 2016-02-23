/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.util

import scala.util.control.NoStackTrace

/**
 * INTERNAL API
 *
 * Convenience base class for exception objects.
 */
private[http] abstract class SingletonException(msg: String) extends RuntimeException(msg) with NoStackTrace {
  def this() = this(null)
}
