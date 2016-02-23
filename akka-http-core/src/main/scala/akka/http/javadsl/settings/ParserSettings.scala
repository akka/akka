/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.settings

import java.util.Optional

import akka.http.impl.engine.parsing.BodyPartParser
import akka.http.impl.settings.ParserSettingsImpl
import java.{ util ⇒ ju }
import akka.http.impl.util.JavaMapping.Implicits._
import scala.annotation.varargs
import scala.collection.JavaConverters._

import akka.http.javadsl.model.{ HttpMethod, StatusCode, Uri }
import com.typesafe.config.Config

/**
 * Public API but not intended for subclassing
 */
abstract class ParserSettings private[akka] () extends BodyPartParser.Settings { self: ParserSettingsImpl ⇒
  def getMaxUriLength: Int
  def getMaxMethodLength: Int
  def getMaxResponseReasonLength: Int
  def getMaxHeaderNameLength: Int
  def getMaxHeaderValueLength: Int
  def getMaxHeaderCount: Int
  def getMaxContentLength: Long
  def getMaxChunkExtLength: Int
  def getMaxChunkSize: Int
  def getUriParsingMode: Uri.ParsingMode
  def getCookieParsingMode: ParserSettings.CookieParsingMode
  def getIllegalHeaderWarnings: Boolean
  def getErrorLoggingVerbosity: ParserSettings.ErrorLoggingVerbosity
  def getHeaderValueCacheLimits: ju.Map[String, Int]
  def getIncludeTlsSessionInfoHeader: Boolean
  def headerValueCacheLimits: Map[String, Int]
  def getCustomMethods: java.util.function.Function[String, Optional[HttpMethod]]
  def getCustomStatusCodes: java.util.function.Function[Int, Optional[StatusCode]]

  // ---

  def withMaxUriLength(newValue: Int): ParserSettings = self.copy(maxUriLength = newValue)
  def withMaxMethodLength(newValue: Int): ParserSettings = self.copy(maxMethodLength = newValue)
  def withMaxResponseReasonLength(newValue: Int): ParserSettings = self.copy(maxResponseReasonLength = newValue)
  def withMaxHeaderNameLength(newValue: Int): ParserSettings = self.copy(maxHeaderNameLength = newValue)
  def withMaxHeaderValueLength(newValue: Int): ParserSettings = self.copy(maxHeaderValueLength = newValue)
  def withMaxHeaderCount(newValue: Int): ParserSettings = self.copy(maxHeaderCount = newValue)
  def withMaxContentLength(newValue: Long): ParserSettings = self.copy(maxContentLength = newValue)
  def withMaxChunkExtLength(newValue: Int): ParserSettings = self.copy(maxChunkExtLength = newValue)
  def withMaxChunkSize(newValue: Int): ParserSettings = self.copy(maxChunkSize = newValue)
  def withUriParsingMode(newValue: Uri.ParsingMode): ParserSettings = self.copy(uriParsingMode = newValue.asScala)
  def withCookieParsingMode(newValue: ParserSettings.CookieParsingMode): ParserSettings = self.copy(cookieParsingMode = newValue.asScala)
  def withIllegalHeaderWarnings(newValue: Boolean): ParserSettings = self.copy(illegalHeaderWarnings = newValue)
  def withErrorLoggingVerbosity(newValue: ParserSettings.ErrorLoggingVerbosity): ParserSettings = self.copy(errorLoggingVerbosity = newValue.asScala)
  def withHeaderValueCacheLimits(newValue: ju.Map[String, Int]): ParserSettings = self.copy(headerValueCacheLimits = newValue.asScala.toMap)
  def withIncludeTlsSessionInfoHeader(newValue: Boolean): ParserSettings = self.copy(includeTlsSessionInfoHeader = newValue)

  // special ---

  @varargs
  def withCustomMethods(methods: HttpMethod*): ParserSettings = {
    val map = methods.map(m ⇒ m.name -> m.asScala).toMap
    self.copy(customMethods = map.get)
  }
  @varargs
  def withCustomStatusCodes(codes: StatusCode*): ParserSettings = {
    val map = codes.map(c ⇒ c.intValue -> c.asScala).toMap
    self.copy(customStatusCodes = map.get)
  }

}

object ParserSettings extends SettingsCompanion[ParserSettings] {
  trait CookieParsingMode
  trait ErrorLoggingVerbosity

  override def create(config: Config): ParserSettings = ParserSettingsImpl(config)
  override def create(configOverrides: String): ParserSettings = ParserSettingsImpl(configOverrides)
}
