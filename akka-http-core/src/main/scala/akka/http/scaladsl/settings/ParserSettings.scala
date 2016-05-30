/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.scaladsl.settings

import java.util
import java.util.Optional
import java.util.function.Function

import akka.http.impl.settings.ParserSettingsImpl
import akka.http.impl.util._
import akka.http.javadsl.model
import akka.http.scaladsl.model._
import akka.http.scaladsl.{ settings ⇒ js }
import com.typesafe.config.Config

import scala.collection.JavaConverters._
import scala.compat.java8.OptionConverters

/**
 * Public API but not intended for subclassing
 */
abstract class ParserSettings private[akka] () extends akka.http.javadsl.settings.ParserSettings { self: ParserSettingsImpl ⇒
  def maxUriLength: Int
  def maxMethodLength: Int
  def maxResponseReasonLength: Int
  def maxHeaderNameLength: Int
  def maxHeaderValueLength: Int
  def maxHeaderCount: Int
  def maxContentLength: Long
  def maxChunkExtLength: Int
  def maxChunkSize: Int
  def uriParsingMode: Uri.ParsingMode
  def cookieParsingMode: ParserSettings.CookieParsingMode
  def illegalHeaderWarnings: Boolean
  def errorLoggingVerbosity: ParserSettings.ErrorLoggingVerbosity
  def headerValueCacheLimits: Map[String, Int]
  def includeTlsSessionInfoHeader: Boolean
  def customMethods: String ⇒ Option[HttpMethod]
  def customStatusCodes: Int ⇒ Option[StatusCode]
  def customMediaTypes: MediaTypes.FindCustom

  /* Java APIs */
  override def getCookieParsingMode: js.ParserSettings.CookieParsingMode = cookieParsingMode
  override def getHeaderValueCacheLimits: util.Map[String, Int] = headerValueCacheLimits.asJava
  override def getMaxChunkExtLength = maxChunkExtLength
  override def getUriParsingMode: akka.http.javadsl.model.Uri.ParsingMode = uriParsingMode
  override def getMaxHeaderCount = maxHeaderCount
  override def getMaxContentLength = maxContentLength
  override def getMaxHeaderValueLength = maxHeaderValueLength
  override def getIncludeTlsSessionInfoHeader = includeTlsSessionInfoHeader
  override def getIllegalHeaderWarnings = illegalHeaderWarnings
  override def getMaxHeaderNameLength = maxHeaderNameLength
  override def getMaxChunkSize = maxChunkSize
  override def getMaxResponseReasonLength = maxResponseReasonLength
  override def getMaxUriLength = maxUriLength
  override def getMaxMethodLength = maxMethodLength
  override def getErrorLoggingVerbosity: js.ParserSettings.ErrorLoggingVerbosity = errorLoggingVerbosity

  override def getCustomMethods = new Function[String, Optional[akka.http.javadsl.model.HttpMethod]] {
    override def apply(t: String) = OptionConverters.toJava(customMethods(t))
  }
  override def getCustomStatusCodes = new Function[Int, Optional[akka.http.javadsl.model.StatusCode]] {
    override def apply(t: Int) = OptionConverters.toJava(customStatusCodes(t))
  }
  override def getCustomMediaTypes = new akka.japi.function.Function2[String, String, Optional[akka.http.javadsl.model.MediaType]] {
    override def apply(mainType: String, subType: String): Optional[model.MediaType] =
      OptionConverters.toJava(customMediaTypes(mainType, subType))
  }

  // ---

  // override for more specific return type
  override def withMaxUriLength(newValue: Int): ParserSettings = self.copy(maxUriLength = newValue)
  override def withMaxMethodLength(newValue: Int): ParserSettings = self.copy(maxMethodLength = newValue)
  override def withMaxResponseReasonLength(newValue: Int): ParserSettings = self.copy(maxResponseReasonLength = newValue)
  override def withMaxHeaderNameLength(newValue: Int): ParserSettings = self.copy(maxHeaderNameLength = newValue)
  override def withMaxHeaderValueLength(newValue: Int): ParserSettings = self.copy(maxHeaderValueLength = newValue)
  override def withMaxHeaderCount(newValue: Int): ParserSettings = self.copy(maxHeaderCount = newValue)
  override def withMaxContentLength(newValue: Long): ParserSettings = self.copy(maxContentLength = newValue)
  override def withMaxChunkExtLength(newValue: Int): ParserSettings = self.copy(maxChunkExtLength = newValue)
  override def withMaxChunkSize(newValue: Int): ParserSettings = self.copy(maxChunkSize = newValue)
  override def withIllegalHeaderWarnings(newValue: Boolean): ParserSettings = self.copy(illegalHeaderWarnings = newValue)
  override def withIncludeTlsSessionInfoHeader(newValue: Boolean): ParserSettings = self.copy(includeTlsSessionInfoHeader = newValue)

  // overloads for idiomatic Scala use
  def withUriParsingMode(newValue: Uri.ParsingMode): ParserSettings = self.copy(uriParsingMode = newValue)
  def withCookieParsingMode(newValue: ParserSettings.CookieParsingMode): ParserSettings = self.copy(cookieParsingMode = newValue)
  def withErrorLoggingVerbosity(newValue: ParserSettings.ErrorLoggingVerbosity): ParserSettings = self.copy(errorLoggingVerbosity = newValue)
  def withHeaderValueCacheLimits(newValue: Map[String, Int]): ParserSettings = self.copy(headerValueCacheLimits = newValue)
  def withCustomMethods(methods: HttpMethod*): ParserSettings = {
    val map = methods.map(m ⇒ m.name → m).toMap
    self.copy(customMethods = map.get)
  }
  def withCustomStatusCodes(codes: StatusCode*): ParserSettings = {
    val map = codes.map(c ⇒ c.intValue → c).toMap
    self.copy(customStatusCodes = map.get)
  }
  def withCustomMediaTypes(types: MediaType*): ParserSettings = {
    val map = types.map(c ⇒ (c.mainType, c.subType) → c).toMap
    self.copy(customMediaTypes = (main, sub) ⇒ map.get((main, sub)))
  }
}

object ParserSettings extends SettingsCompanion[ParserSettings] {
  trait CookieParsingMode extends akka.http.javadsl.settings.ParserSettings.CookieParsingMode
  object CookieParsingMode {
    case object RFC6265 extends CookieParsingMode
    case object Raw extends CookieParsingMode

    def apply(mode: String): CookieParsingMode = mode.toRootLowerCase match {
      case "rfc6265" ⇒ RFC6265
      case "raw"     ⇒ Raw
    }
  }

  trait ErrorLoggingVerbosity extends akka.http.javadsl.settings.ParserSettings.ErrorLoggingVerbosity
  object ErrorLoggingVerbosity {
    case object Off extends ErrorLoggingVerbosity
    case object Simple extends ErrorLoggingVerbosity
    case object Full extends ErrorLoggingVerbosity

    def apply(string: String): ErrorLoggingVerbosity =
      string.toRootLowerCase match {
        case "off"    ⇒ Off
        case "simple" ⇒ Simple
        case "full"   ⇒ Full
        case x        ⇒ throw new IllegalArgumentException(s"[$x] is not a legal `error-logging-verbosity` setting")
      }
  }

  override def apply(config: Config): ParserSettings = ParserSettingsImpl(config)
  override def apply(configOverrides: String): ParserSettings = ParserSettingsImpl(configOverrides)
}
