/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.util

import com.typesafe.config.Config
import scala.concurrent.duration.{ FiniteDuration, Duration }
import akka.ConfigurationException

/**
 * INTERNAL API
 */
private[http] class EnhancedConfig(val underlying: Config) extends AnyVal {

  def getPotentiallyInfiniteDuration(path: String): Duration = underlying.getString(path) match {
    case "infinite" ⇒ Duration.Inf
    case x          ⇒ Duration(x)
  }

  def getFiniteDuration(path: String): FiniteDuration = Duration(underlying.getString(path)) match {
    case x: FiniteDuration ⇒ x
    case _                 ⇒ throw new ConfigurationException(s"Config setting '$path' must be a finite duration")
  }

  def getPossiblyInfiniteInt(path: String): Int = underlying.getString(path) match {
    case "infinite" ⇒ Int.MaxValue
    case x          ⇒ underlying.getInt(path)
  }

  def getIntBytes(path: String): Int = {
    val value: Long = underlying getBytes path
    if (value <= Int.MaxValue) value.toInt
    else throw new ConfigurationException(s"Config setting '$path' must not be larger than ${Int.MaxValue}")
  }

  def getPossiblyInfiniteIntBytes(path: String): Int = underlying.getString(path) match {
    case "infinite" ⇒ Int.MaxValue
    case x          ⇒ getIntBytes(path)
  }

  def getPossiblyInfiniteBytes(path: String): Long = underlying.getString(path) match {
    case "infinite" ⇒ Long.MaxValue
    case x          ⇒ underlying.getBytes(path)
  }
}
