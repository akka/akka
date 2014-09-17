/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.util

import akka.http.server._

/**
 * ApplyConverter allows generic conversion of functions of type `(T1, T2, ...) => Route` to
 * `(TupleX(T1, T2, ...)) => Route`.
 */
abstract class ApplyConverter[L] {
  type In
  def apply(f: In): L ⇒ Route
}

object ApplyConverter extends ApplyConverterInstances