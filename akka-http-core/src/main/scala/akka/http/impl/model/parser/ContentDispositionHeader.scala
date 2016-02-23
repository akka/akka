/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.model.parser

import akka.parboiled2.Parser
import akka.http.scaladsl.model.headers._

private[parser] trait ContentDispositionHeader { this: Parser with CommonRules with CommonActions ⇒

  // http://tools.ietf.org/html/rfc6266#section-4.1
  def `content-disposition` = rule {
    `disposition-type` ~ zeroOrMore(ws(';') ~ `disposition-parm`) ~ EOI ~> (_.toMap) ~> (`Content-Disposition`(_, _))
  }

  def `disposition-type` = rule(
    ignoreCase("inline") ~ OWS ~ push(ContentDispositionTypes.inline)
      | ignoreCase("attachment") ~ OWS ~ push(ContentDispositionTypes.attachment)
      | ignoreCase("form-data") ~ OWS ~ push(ContentDispositionTypes.`form-data`)
      | `disp-ext-type` ~> (ContentDispositionTypes.Ext(_)))

  def `disp-ext-type` = rule { token }

  def `disposition-parm` = rule { (`filename-parm` | `disp-ext-parm`) ~> (_ -> _) }

  def `filename-parm` = rule(
    ignoreCase("filename") ~ OWS ~ ws('=') ~ push("filename") ~ word
      | ignoreCase("filename*") ~ OWS ~ ws('=') ~ push("filename") ~ `ext-value`)

  def `disp-ext-parm` = rule(
    token ~ ws('=') ~ word
      | `ext-token` ~ ws('=') ~ `ext-value`)

  def `ext-token` = rule { // token which ends with '*'
    token ~> (s ⇒ test(s endsWith "*") ~ push(s))
  }

  def `ext-value` = rule { word } // support full `ext-value` notation from http://tools.ietf.org/html/rfc5987#section-3.2.1
}