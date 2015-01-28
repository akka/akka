/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import akka.stream.scaladsl
import akka.japi.Pair

object Keep {
  private val _left = new japi.Function2[Any, Any, Any] with ((Any, Any) ⇒ Any) { def apply(l: Any, r: Any) = l }
  private val _right = new japi.Function2[Any, Any, Any] with ((Any, Any) ⇒ Any) { def apply(l: Any, r: Any) = r }
  private val _both = new japi.Function2[Any, Any, Any] with ((Any, Any) ⇒ Any) { def apply(l: Any, r: Any) = new akka.japi.Pair(l, r) }

  def left[L, R]: japi.Function2[L, R, L] = _left.asInstanceOf[japi.Function2[L, R, L]]
  def right[L, R]: japi.Function2[L, R, R] = _right.asInstanceOf[japi.Function2[L, R, R]]
  def both[L, R]: japi.Function2[L, R, L Pair R] = _both.asInstanceOf[japi.Function2[L, R, L Pair R]]
}
