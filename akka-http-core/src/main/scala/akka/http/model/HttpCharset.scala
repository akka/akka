/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

import language.implicitConversions
import scala.collection.immutable
import java.nio.charset.Charset
import akka.http.util._
import java.lang.Iterable

/**
 * A charset range as encountered in `Accept-Charset`. Can either be a single charset, or `*`
 * if all charsets are supported and optionally a qValue for selecting this choice.
 */
sealed abstract class HttpCharsetRange extends japi.HttpCharsetRange with ValueRenderable with WithQValue[HttpCharsetRange] {
  def qValue: Float
  def matches(charset: HttpCharset): Boolean

  /** Java API */
  def matches(charset: japi.HttpCharset): Boolean = {
    import japi.JavaMapping.Implicits._
    matches(charset.asScala)
  }
}

object HttpCharsetRange {
  case class `*`(qValue: Float) extends HttpCharsetRange {
    require(0.0f <= qValue && qValue <= 1.0f, "qValue must be >= 0 and <= 1.0")
    final def render[R <: Rendering](r: R): r.type = if (qValue < 1.0f) r ~~ "*;q=" ~~ qValue else r ~~ '*'
    def matches(charset: HttpCharset) = true
    def matchesAll: Boolean = true

    def withQValue(qValue: Float) =
      if (qValue == 1.0f) `*` else if (qValue != this.qValue) `*`(qValue.toFloat) else this
  }
  object `*` extends `*`(1.0f)

  final case class One(charset: HttpCharset, qValue: Float) extends HttpCharsetRange {
    require(0.0f <= qValue && qValue <= 1.0f, "qValue must be >= 0 and <= 1.0")
    def matches(charset: HttpCharset) = this.charset.value.equalsIgnoreCase(charset.value)
    def matchesAll: Boolean = false

    def withQValue(qValue: Float) = One(charset, qValue)
    def render[R <: Rendering](r: R): r.type = if (qValue < 1.0f) r ~~ charset ~~ ";q=" ~~ qValue else r ~~ charset
  }

  implicit def apply(charset: HttpCharset): HttpCharsetRange = apply(charset, 1.0f)
  def apply(charset: HttpCharset, qValue: Float): HttpCharsetRange = One(charset, qValue)
}

final case class HttpCharset private[http] (override val value: String)(val aliases: immutable.Seq[String])
  extends japi.HttpCharset with SingletonValueRenderable with WithQValue[HttpCharsetRange] {
  @transient private[this] var _nioCharset: Charset = Charset.forName(value)
  def nioCharset: Charset = _nioCharset

  private def readObject(in: java.io.ObjectInputStream): Unit = {
    in.defaultReadObject()
    _nioCharset = Charset.forName(value)
  }

  def withQValue(qValue: Float): HttpCharsetRange = HttpCharsetRange(this, qValue.toFloat)

  /** Java API */
  def getAliases: Iterable[String] = {
    import collection.JavaConverters._
    aliases.asJava
  }
}

object HttpCharset {
  def custom(value: String, aliases: String*): Option[HttpCharset] =
    try Some(HttpCharset(value)(immutable.Seq(aliases: _*)))
    catch {
      // per documentation all exceptions thrown by `Charset.forName` are IllegalArgumentExceptions
      case e: IllegalArgumentException ⇒ None
    }
}

// see http://www.iana.org/assignments/character-sets
object HttpCharsets extends ObjectRegistry[String, HttpCharset] {
  def register(charset: HttpCharset): HttpCharset = {
    charset.aliases.foreach(alias ⇒ register(alias.toLowerCase, charset))
    register(charset.value.toLowerCase, charset)
  }

  /** Register standard charset that is required to be supported on all platforms */
  private def register(value: String)(aliases: String*): HttpCharset =
    register(HttpCharset(value)(immutable.Seq(aliases: _*)))

  /** Register non-standard charsets that may be missing on some platforms */
  private def tryRegister(value: String)(aliases: String*): Unit =
    try register(value)(aliases: _*)
    catch {
      case e: java.nio.charset.UnsupportedCharsetException ⇒ // ignore
    }

  // format: OFF
  // Only those first 6 are standard charsets known to be supported on all platforms
  // by Javadoc of java.nio.charset.Charset
  // see http://docs.oracle.com/javase/6/docs/api/java/nio/charset/Charset.html
  val `US-ASCII`     = register("US-ASCII")("iso-ir-6", "ANSI_X3.4-1986", "ISO_646.irv:1991", "ASCII", "ISO646-US", "us", "IBM367", "cp367", "csASCII")
  val `ISO-8859-1`   = register("ISO-8859-1")("iso-ir-100", "ISO_8859-1", "latin1", "l1", "IBM819", "CP819", "csISOLatin1")
  val `UTF-8`        = register("UTF-8")("UTF8")
  val `UTF-16`       = register("UTF-16")("UTF16")
  val `UTF-16BE`     = register("UTF-16BE")()
  val `UTF-16LE`     = register("UTF-16LE")()

  // those are not necessarily supported on every platform so we can't expose them as constants here
  // but we try to register them so that all the aliases are registered
  tryRegister("ISO-8859-2")("iso-ir-101", "ISO_8859-2", "latin2", "l2", "csISOLatin2")
  tryRegister("ISO-8859-3")("iso-ir-109", "ISO_8859-3", "latin3", "l3", "csISOLatin3")
  tryRegister("ISO-8859-4")("iso-ir-110", "ISO_8859-4", "latin4", "l4", "csISOLatin4")
  tryRegister("ISO-8859-5")("iso-ir-144", "ISO_8859-5", "cyrillic", "csISOLatinCyrillic")
  tryRegister("ISO-8859-6")("iso-ir-127", "ISO_8859-6", "ECMA-114", "ASMO-708", "arabic", "csISOLatinArabic")
  tryRegister("ISO-8859-7")("iso-ir-126", "ISO_8859-7", "ELOT_928", "ECMA-118", "greek", "greek8", "csISOLatinGreek")
  tryRegister("ISO-8859-8")("iso-ir-138", "ISO_8859-8", "hebrew", "csISOLatinHebrew")
  tryRegister("ISO-8859-9")("iso-ir-148", "ISO_8859-9", "latin5", "l5", "csISOLatin5")
  tryRegister("ISO-8859-10")("iso-ir-157", "l6", "ISO_8859-10", "csISOLatin6", "latin6")

  tryRegister("UTF-32")("UTF32")
  tryRegister("UTF-32BE")()
  tryRegister("UTF-32LE")()
  tryRegister("windows-1250")("cp1250", "cp5346")
  tryRegister("windows-1251")("cp1251", "cp5347")
  tryRegister("windows-1252")("cp1252", "cp5348")
  tryRegister("windows-1253")("cp1253", "cp5349")
  tryRegister("windows-1254")("cp1254", "cp5350")
  tryRegister("windows-1257")("cp1257", "cp5353")
  // format: ON
}
