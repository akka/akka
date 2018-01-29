/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.util

import akka.annotation.InternalApi

import scala.util.matching.Regex

/**
 * INTERNAL API
 */
@InternalApi
private[http] class EnhancedRegex(val regex: Regex) extends AnyVal {
  def groupCount = regex.pattern.matcher("").groupCount()
}
