/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model
package parser

import akka.parboiled2.Parser
import headers._
import CacheDirectives._

private[parser] trait CacheControlHeader { this: Parser with CommonRules with CommonActions ⇒

  // http://tools.ietf.org/html/rfc7234#section-5.2
  def `cache-control` = rule {
    oneOrMore(`cache-directive`).separatedBy(listSep) ~ EOI ~> (`Cache-Control`(_))
  }

  def `cache-directive` = rule(
    "no-cache" ~ push(`no-cache`)
      | "no-store" ~ push(`no-store`)
      | "no-transform" ~ push(`no-transform`)
      | "max-age=" ~ `delta-seconds` ~> (`max-age`(_))
      | "max-stale" ~ optional(ws('=') ~ `delta-seconds`) ~> (`max-stale`(_))
      | "min-fresh=" ~ `delta-seconds` ~> (`min-fresh`(_))
      | "only-if-cached" ~ push(`only-if-cached`)
      | "public" ~ push(`public`)
      | "private" ~ (ws('=') ~ `field-names` ~> (`private`(_: _*)) | push(`private`(Nil: _*)))
      | "no-cache" ~ (ws('=') ~ `field-names` ~> (`no-cache`(_: _*)) | push(`no-cache`(Nil: _*)))
      | "must-revalidate" ~ push(`must-revalidate`)
      | "proxy-revalidate" ~ push(`proxy-revalidate`)
      | "s-maxage=" ~ `delta-seconds` ~> (`s-maxage`(_))
      | token ~ optional(ws('=') ~ word) ~> (CacheDirective.custom(_, _)))

  def `field-names` = rule { oneOrMore(word).separatedBy(listSep) }
}