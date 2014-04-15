/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.util

import scala.util.control.NoStackTrace

/**
 * Convenience base class for exception objects.
 */
abstract class SingletonException(msg: String) extends RuntimeException(msg) with NoStackTrace {
  def this() = this(null)
}
