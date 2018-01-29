/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.util

import akka.annotation.InternalApi

import scala.util.control.NoStackTrace

/**
 * INTERNAL API
 *
 * Convenience base class for exception objects.
 */
@InternalApi
private[http] abstract class SingletonException(msg: String) extends RuntimeException(msg) with NoStackTrace {
  def this() = this(null)
}
