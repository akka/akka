/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

import language.implicitConversions
import akka.util.{Bytes, ByteString}

/**
 * Models the entity (aka "body" or "content) of an HTTP message.
 */
sealed trait HttpEntity {
  def isEmpty: Boolean
  def nonEmpty: Boolean = !isEmpty
  def data: Bytes
  def flatMap(f: HttpEntity.NonEmpty ⇒ HttpEntity): HttpEntity
  def orElse(other: HttpEntity): HttpEntity
  def asString: String
  def asString(defaultCharset: HttpCharset): String
  def toOption: Option[HttpEntity.NonEmpty]
}

object HttpEntity {
  implicit def apply(string: String): HttpEntity = apply(ContentTypes.`text/plain(UTF-8)`, string)
  implicit def apply(bytes: Array[Byte]): HttpEntity = apply(ByteString(bytes))
  implicit def apply(data: Bytes): HttpEntity = apply(ContentTypes.`application/octet-stream`, data)
  def apply(contentType: ContentType, string: String): HttpEntity =
    if (string.isEmpty) Empty else apply(contentType, ByteString(string.getBytes(contentType.charset.nioCharset)))
  def apply(contentType: ContentType, bytes: Array[Byte]): HttpEntity = apply(contentType, bytes)
  def apply(contentType: ContentType, bytes: ByteString): HttpEntity = apply(contentType, bytes)
  def apply(contentType: ContentType, data: Bytes): HttpEntity =
    data match {
      case x if x.nonEmpty ⇒ new NonEmpty(contentType, x)
      case _               ⇒ Empty
    }

  implicit def flatten(optionalEntity: Option[HttpEntity]): HttpEntity =
    optionalEntity match {
      case Some(body) ⇒ body
      case None       ⇒ Empty
    }

  /**
   * Models an empty entity.
   */
  case object Empty extends HttpEntity {
    def isEmpty = true
    def data = Bytes.Empty
    def flatMap(f: HttpEntity.NonEmpty ⇒ HttpEntity): HttpEntity = this
    def orElse(other: HttpEntity): HttpEntity = other
    def asString = ""
    def asString(defaultCharset: HttpCharset) = ""
    def toOption = None
  }

  /**
   * Models a non-empty entity. The buffer array is guaranteed to have a size greater than zero.
   * CAUTION: Even though the byte array is directly exposed for performance reasons all instances of this class are
   * assumed to be immutable! spray never modifies the buffer contents after an HttpEntity.NonEmpty instance has been created.
   * If you modify the buffer contents by writing to the array things WILL BREAK!
   */
  case class NonEmpty private[HttpEntity] (contentType: ContentType, data: Bytes) extends HttpEntity {
    require(data.nonEmpty)
    def isEmpty = false
    def flatMap(f: HttpEntity.NonEmpty ⇒ HttpEntity): HttpEntity = f(this)
    def orElse(other: HttpEntity): HttpEntity = this
    def asString = data.asString(contentType.charset)
    def asString(defaultCharset: HttpCharset) = data.asString(contentType.definedCharset getOrElse defaultCharset)
    def toOption = Some(this)
    override def toString =
      "HttpEntity(" + contentType + ',' + (if (data.longLength > 500) asString.take(500) + "..." else asString) + ')'
  }
}
