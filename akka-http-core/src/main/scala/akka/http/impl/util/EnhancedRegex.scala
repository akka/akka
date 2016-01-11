/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.util

import scala.util.matching.Regex

/**
 * INTERNAL API
 */
private[http] class EnhancedRegex(val regex: Regex) extends AnyVal {
  def groupCount = regex.pattern.matcher("").groupCount()
}