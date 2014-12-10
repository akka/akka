/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

import language.implicitConversions
import akka.http.util._
import akka.japi.{ Option ⇒ JOption }

import akka.http.model.japi.JavaMapping.Implicits._

final case class ContentTypeRange(mediaRange: MediaRange, charsetRange: HttpCharsetRange) extends ValueRenderable {
  def matches(contentType: ContentType) =
    mediaRange.matches(contentType.mediaType) && charsetRange.matches(contentType.charset)

  def render[R <: Rendering](r: R): r.type = charsetRange match {
    case HttpCharsetRange.`*` ⇒ r ~~ mediaRange
    case x                    ⇒ r ~~ mediaRange ~~ ContentType.`; charset=` ~~ x
  }

  /**
   * Returns a [[ContentType]] instance which fits this range.
   */
  def specimen: ContentType = ContentType(mediaRange.specimen, charsetRange.specimen)
}

object ContentTypeRange {
  val `*` = ContentTypeRange(MediaRanges.`*/*`)

  implicit def apply(mediaType: MediaType): ContentTypeRange = apply(mediaType, HttpCharsetRange.`*`)
  implicit def apply(mediaRange: MediaRange): ContentTypeRange = apply(mediaRange, HttpCharsetRange.`*`)
  implicit def apply(contentType: ContentType): ContentTypeRange =
    contentType.definedCharset match {
      case Some(charset) ⇒ apply(contentType.mediaType, charset)
      case None          ⇒ ContentTypeRange(contentType.mediaType)
    }
}

final case class ContentType(mediaType: MediaType, definedCharset: Option[HttpCharset]) extends japi.ContentType with ValueRenderable {
  def render[R <: Rendering](r: R): r.type = definedCharset match {
    case Some(cs) ⇒ r ~~ mediaType ~~ ContentType.`; charset=` ~~ cs
    case _        ⇒ r ~~ mediaType
  }
  def charset: HttpCharset = definedCharset getOrElse HttpCharsets.`UTF-8`

  def withMediaType(mediaType: MediaType) =
    if (mediaType != this.mediaType) copy(mediaType = mediaType) else this
  def withCharset(charset: HttpCharset) =
    if (definedCharset.isEmpty || charset != definedCharset.get) copy(definedCharset = Some(charset)) else this
  def withoutDefinedCharset =
    if (definedCharset.isDefined) copy(definedCharset = None) else this

  /** Java API */
  def getDefinedCharset: JOption[japi.HttpCharset] = definedCharset.asJava
}

object ContentType {
  private[http] case object `; charset=` extends SingletonValueRenderable

  def apply(mediaType: MediaType, charset: HttpCharset): ContentType = apply(mediaType, Some(charset))
  implicit def apply(mediaType: MediaType): ContentType = apply(mediaType, None)
}

object ContentTypes {
  // RFC4627 defines JSON to always be UTF encoded, we always render JSON to UTF-8
  val `application/json` = ContentType(MediaTypes.`application/json`, HttpCharsets.`UTF-8`)
  val `text/plain` = ContentType(MediaTypes.`text/plain`)
  val `text/plain(UTF-8)` = ContentType(MediaTypes.`text/plain`, HttpCharsets.`UTF-8`)
  val `application/octet-stream` = ContentType(MediaTypes.`application/octet-stream`)

  // used for explicitly suppressing the rendering of Content-Type headers on requests and responses
  val NoContentType = ContentType(MediaTypes.NoMediaType)
}
