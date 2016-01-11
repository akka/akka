/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
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
