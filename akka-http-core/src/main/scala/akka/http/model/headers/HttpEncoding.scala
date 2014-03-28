/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model
package headers

import language.implicitConversions
import akka.http.util._

sealed abstract class HttpEncodingRange extends ValueRenderable with WithQValue[HttpEncodingRange] {
  def qValue: Float
  def matches(encoding: HttpEncoding): Boolean
}

object HttpEncodingRange {
  case class `*`(qValue: Float) extends HttpEncodingRange {
    def render[R <: Rendering](r: R): r.type = if (qValue < 1.0f) r ~~ "*;q=" ~~ qValue else r ~~ '*'
    def matches(encoding: HttpEncoding) = true
    def withQValue(qValue: Float) =
      if (qValue == 1.0f) `*` else if (qValue != this.qValue) `*`(qValue.toFloat) else this
  }
  object `*` extends `*`(1.0f)

  case class One(encoding: HttpEncoding, qValue: Float) extends HttpEncodingRange {
    def matches(encoding: HttpEncoding) = this.encoding.value.equalsIgnoreCase(encoding.value)
    def withQValue(qValue: Float) = One(encoding, qValue)
    def render[R <: Rendering](r: R): r.type = if (qValue < 1.0f) r ~~ encoding ~~ ";q=" ~~ qValue else r ~~ encoding
  }

  implicit def apply(encoding: HttpEncoding): HttpEncodingRange = apply(encoding, 1.0f)
  def apply(encoding: HttpEncoding, qValue: Float): HttpEncodingRange = One(encoding, qValue)
}

case class HttpEncoding private[http] (value: String) extends LazyValueBytesRenderable with WithQValue[HttpEncodingRange] {
  def withQValue(qValue: Float): HttpEncodingRange = HttpEncodingRange(this, qValue.toFloat)
}

object HttpEncoding {
  def custom(value: String): HttpEncoding = apply(value)
}

// see http://www.iana.org/assignments/http-parameters/http-parameters.xml
object HttpEncodings extends ObjectRegistry[String, HttpEncoding] {
  def register(encoding: HttpEncoding): HttpEncoding =
    register(encoding.value.toLowerCase, encoding)

  @deprecated("Use HttpEncodingRange.`*` instead", "1.x-RC3")
  val `*`: HttpEncodingRange = HttpEncodingRange.`*`

  private def register(value: String): HttpEncoding = register(HttpEncoding(value))

  // format: OFF
  val compress      = register("compress")
  val chunked       = register("chunked")
  val deflate       = register("deflate")
  val gzip          = register("gzip")
  val identity      = register("identity")
  val `x-compress`  = register("x-compress")
  val `x-zip`       = register("x-zip")
  // format: ON
}
