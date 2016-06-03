/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
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

  @tailrec private def contentType(
    main:    String,
    sub:     String,
    params:  Seq[(String, String)],
    charset: Option[HttpCharset]   = None,
    builder: StringMapBuilder      = null): ContentType =
    params match {
      case Nil ⇒
        val parameters = if (builder eq null) Map.empty[String, String] else builder.result()
        getMediaType(main, sub, charset.isDefined, parameters) match {
          case x: MediaType.Binary           ⇒ ContentType.Binary(x)
          case x: MediaType.WithFixedCharset ⇒ ContentType.WithFixedCharset(x)
          case x: MediaType.WithOpenCharset ⇒
            // if we have an open charset media-type but no charset parameter we default to UTF-8
            val cs = if (charset.isDefined) charset.get else HttpCharsets.`UTF-8`
            ContentType.WithCharset(x, cs)
        }

      case Seq(("charset", value), tail @ _*) ⇒
        contentType(main, sub, tail, Some(getCharset(value)), builder)

      case Seq(kvp, tail @ _*) ⇒
        val b = if (builder eq null) Map.newBuilder[String, String] else builder
        b += kvp
        contentType(main, sub, tail, charset, b)
    }
}