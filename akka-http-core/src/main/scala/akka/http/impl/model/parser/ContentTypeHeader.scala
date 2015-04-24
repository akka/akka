/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.model.parser

import scala.annotation.tailrec
import akka.parboiled2.Parser
import akka.http.scaladsl.model._

private[parser] trait ContentTypeHeader { this: Parser with CommonRules with CommonActions ⇒

  // http://tools.ietf.org/html/rfc7231#section-3.1.1.5
  def `content-type` = rule {
    `media-type` ~ EOI ~> ((main, sub, params) ⇒ headers.`Content-Type`(contentType(main, sub, params)))
  }

  @tailrec private def contentType(main: String,
                                   sub: String,
                                   params: Seq[(String, String)],
                                   charset: Option[HttpCharset] = None,
                                   builder: StringMapBuilder = null): ContentType =
    params match {
      case Nil ⇒
        val parameters = if (builder eq null) Map.empty[String, String] else builder.result()
        val mediaType = getMediaType(main, sub, parameters)
        ContentType(mediaType, charset)

      case Seq(("charset", value), tail @ _*) ⇒
        contentType(main, sub, tail, Some(getCharset(value)), builder)

      case Seq(kvp, tail @ _*) ⇒
        val b = if (builder eq null) Map.newBuilder[String, String] else builder
        b += kvp
        contentType(main, sub, tail, charset, b)
    }
}