/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.util

import scala.util.matching.Regex

/**
 * INTERNAL API
 */
private[http] class EnhancedRegex(regex: Regex) {
  def groupCount = regex.pattern.matcher("").groupCount()
}