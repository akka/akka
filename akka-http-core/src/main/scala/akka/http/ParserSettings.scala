/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import java.util.Locale
import akka.actor.ActorSystem
import com.typesafe.config.Config
import scala.collection.JavaConverters._
import akka.http.scaladsl.model.{ StatusCode, HttpMethod, Uri }
import akka.http.impl.util._
import akka.http.impl.engine.parsing.HttpHeaderParser

final case class ParserSettings(
  maxUriLength: Int,
  maxMethodLength: Int,
  maxResponseReasonLength: Int,
  maxHeaderNameLength: Int,
  maxHeaderValueLength: Int,
  maxHeaderCount: Int,
  maxContentLength: Long,
  maxChunkExtLength: Int,
  maxChunkSize: Int,
  uriParsingMode: Uri.ParsingMode,
  illegalHeaderWarnings: Boolean,
  errorLoggingVerbosity: ParserSettings.ErrorLoggingVerbosity,
  headerValueCacheLimits: Map[String, Int],
  customMethods: String ⇒ Option[HttpMethod],
  customStatusCodes: Int ⇒ Option[StatusCode]) extends HttpHeaderParser.Settings {

  require(maxUriLength > 0, "max-uri-length must be > 0")
  require(maxMethodLength > 0, "max-method-length must be > 0")
  require(maxResponseReasonLength > 0, "max-response-reason-length must be > 0")
  require(maxHeaderNameLength > 0, "max-header-name-length must be > 0")
  require(maxHeaderValueLength > 0, "max-header-value-length must be > 0")
  require(maxHeaderCount > 0, "max-header-count must be > 0")
  require(maxContentLength > 0, "max-content-length must be > 0")
  require(maxChunkExtLength > 0, "max-chunk-ext-length must be > 0")
  require(maxChunkSize > 0, "max-chunk-size must be > 0")

  val defaultHeaderValueCacheLimit: Int = headerValueCacheLimits("default")

  def headerValueCacheLimit(headerName: String): Int =
    headerValueCacheLimits.getOrElse(headerName, defaultHeaderValueCacheLimit)

  def withCustomMethods(methods: HttpMethod*): ParserSettings = {
    val map = methods.map(m ⇒ m.name -> m).toMap
    copy(customMethods = map.get)
  }
  def withCustomStatusCodes(codes: StatusCode*): ParserSettings = {
    val map = codes.map(c ⇒ c.intValue -> c).toMap
    copy(customStatusCodes = map.get)
  }
}

object ParserSettings extends SettingsCompanion[ParserSettings]("akka.http.parsing") {
  def fromSubConfig(c: Config) = {
    val cacheConfig = c getConfig "header-cache"

    apply(
      c getIntBytes "max-uri-length",
      c getIntBytes "max-method-length",
      c getIntBytes "max-response-reason-length",
      c getIntBytes "max-header-name-length",
      c getIntBytes "max-header-value-length",
      c getIntBytes "max-header-count",
      c getPossiblyInfiniteBytes "max-content-length",
      c getIntBytes "max-chunk-ext-length",
      c getIntBytes "max-chunk-size",
      Uri.ParsingMode(c getString "uri-parsing-mode"),
      c getBoolean "illegal-header-warnings",
      ErrorLoggingVerbosity(c getString "error-logging-verbosity"),
      cacheConfig.entrySet.asScala.map(kvp ⇒ kvp.getKey -> cacheConfig.getInt(kvp.getKey))(collection.breakOut),
      _ ⇒ None,
      _ ⇒ None)
  }

  sealed trait ErrorLoggingVerbosity
  object ErrorLoggingVerbosity {
    case object Off extends ErrorLoggingVerbosity
    case object Simple extends ErrorLoggingVerbosity
    case object Full extends ErrorLoggingVerbosity

    def apply(string: String): ErrorLoggingVerbosity =
      string.toLowerCase(Locale.ROOT) match {
        case "off"    ⇒ Off
        case "simple" ⇒ Simple
        case "full"   ⇒ Full
        case x        ⇒ throw new IllegalArgumentException(s"[$x] is not a legal `error-logging-verbosity` setting")
      }
  }

  /**
   * Creates an instance of ParserSettings using the configuration provided by the given
   * ActorSystem.
   *
   * Java API
   */
  def create(system: ActorSystem): ParserSettings = ParserSettings(system)

  /**
   * Creates an instance of ParserSettings using the given Config.
   *
   * Java API
   */
  def create(config: Config): ParserSettings = ParserSettings(config)

  /**
   * Create an instance of ParserSettings using the given String of config overrides to override
   * settings set in the class loader of this class (i.e. by application.conf or reference.conf files in
   * the class loader of this class).
   *
   * Java API
   */
  def create(configOverrides: String): ParserSettings = ParserSettings(configOverrides)
}

