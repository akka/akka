/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.model

import language.implicitConversions
import akka.http.impl.util._
import akka.japi.{ Option ⇒ JOption }
import akka.http.javadsl.{ model ⇒ jm }
import akka.http.impl.util.JavaMapping.Implicits._

final case class ContentTypeRange(mediaRange: MediaRange, charsetRange: HttpCharsetRange) extends jm.ContentTypeRange with ValueRenderable {
  def matches(contentType: jm.ContentType) =
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

abstract case class ContentType private (mediaType: MediaType, definedCharset: Option[HttpCharset]) extends jm.ContentType with ValueRenderable {
  private[http] def render[R <: Rendering](r: R): r.type = definedCharset match {
    case Some(cs) ⇒ r ~~ mediaType ~~ ContentType.`; charset=` ~~ cs
    case _        ⇒ r ~~ mediaType
  }
  def charset: HttpCharset = definedCharset orElse mediaType.encoding.charset getOrElse HttpCharsets.`UTF-8`

  def hasOpenCharset: Boolean = definedCharset.isEmpty && mediaType.encoding == MediaType.Encoding.Open

  def withMediaType(mediaType: MediaType) =
    if (mediaType != this.mediaType) ContentType(mediaType, definedCharset) else this
  def withCharset(charset: HttpCharset) =
    if (definedCharset.isEmpty || charset != definedCharset.get) ContentType(mediaType, charset) else this
  def withoutDefinedCharset =
    if (definedCharset.isDefined) ContentType(mediaType, None) else this
  def withDefaultCharset(charset: HttpCharset) =
    if (mediaType.encoding == MediaType.Encoding.Open && definedCharset.isEmpty) ContentType(mediaType, charset) else this

  /** Java API */
  def getDefinedCharset: JOption[jm.HttpCharset] = definedCharset.asJava
}

object ContentType {
  private[http] case object `; charset=` extends SingletonValueRenderable

  implicit def apply(mediaType: MediaType): ContentType = apply(mediaType, None)

  def apply(mediaType: MediaType, charset: HttpCharset): ContentType = apply(mediaType, Some(charset))

  def apply(mediaType: MediaType, charset: Option[HttpCharset]): ContentType = {
    val definedCharset =
      charset match {
        case None ⇒ None
        case Some(cs) ⇒ mediaType.encoding match {
          case MediaType.Encoding.Open        ⇒ charset
          case MediaType.Encoding.Fixed(`cs`) ⇒ None
          case x ⇒ throw new IllegalArgumentException(
            s"MediaType $mediaType has a $x encoding and doesn't allow a custom `charset` $cs")
        }
      }
    new ContentType(mediaType, definedCharset) {}
  }

  /**
   * Tries to parse a ``ContentType`` value from the given String. Returns ``Right(contentType)`` if successful and
   * ``Left(errors)`` otherwise.
   */
  def parse(value: String): Either[List[ErrorInfo], ContentType] =
    headers.`Content-Type`.parseFromValueString(value).right.map(_.contentType)
}

object ContentTypes {
  val `application/json` = ContentType(MediaTypes.`application/json`)
  val `application/octet-stream` = ContentType(MediaTypes.`application/octet-stream`)

  val `text/plain` = ContentType(MediaTypes.`text/plain`)
  val `text/plain(UTF-8)` = ContentType(MediaTypes.`text/plain`, HttpCharsets.`UTF-8`)
  val `text/html` = ContentType(MediaTypes.`text/html`)
  val `text/xml` = ContentType(MediaTypes.`text/xml`)

  val `application/x-www-form-urlencoded` = ContentType(MediaTypes.`application/x-www-form-urlencoded`)
  val `multipart/form-data` = ContentType(MediaTypes.`multipart/form-data`)

  // used for explicitly suppressing the rendering of Content-Type headers on requests and responses
  val NoContentType = ContentType(MediaTypes.NoMediaType)
}
