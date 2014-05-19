/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.util

import scala.concurrent.duration.Duration
import com.typesafe.config.Config

class EnhancedConfig(val underlying: Config) extends AnyVal {

  def getDuration(path: String): Duration = underlying.getString(path) match {
    case "infinite" ⇒ Duration.Inf
    case x          ⇒ Duration(x)
  }

  def getPossiblyInfiniteInt(path: String): Int = underlying.getString(path) match {
    case "infinite" ⇒ Int.MaxValue
    case x          ⇒ underlying.getInt(path)
  }

  def getIntBytes(path: String): Int = {
    val value: Long = underlying getBytes path
    if (value <= Int.MaxValue) value.toInt
    else sys.error(s"Config setting '$path' must not be larger than ${Int.MaxValue}")
  }

  def getPossiblyInfiniteIntBytes(path: String): Int = underlying.getString(path) match {
    case "infinite" ⇒ Int.MaxValue
    case x          ⇒ getIntBytes(path)
  }

  def getPossiblyInfiniteLongBytes(path: String): Long = underlying.getString(path) match {
    case "infinite" ⇒ Long.MaxValue
    case x          ⇒ getIntBytes(path)
  }
}
