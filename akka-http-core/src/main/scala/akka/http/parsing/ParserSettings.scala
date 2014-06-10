/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.parsing

import com.typesafe.config.Config
import scala.collection.JavaConverters._
import akka.http.model.Uri
import akka.http.util._

final case class ParserSettings(
  maxUriLength: Int,
  maxResponseReasonLength: Int,
  maxHeaderNameLength: Int,
  maxHeaderValueLength: Int,
  maxHeaderCount: Int,
  maxContentLength: Long,
  maxChunkExtLength: Int,
  maxChunkSize: Int,
  uriParsingMode: Uri.ParsingMode,
  illegalHeaderWarnings: Boolean,
  sslSessionInfoHeader: Boolean,
  headerValueCacheLimits: Map[String, Int]) {

  require(maxUriLength > 0, "max-uri-length must be > 0")
  require(maxResponseReasonLength > 0, "max-response-reason-length must be > 0")
  require(maxHeaderNameLength > 0, "max-header-name-length must be > 0")
  require(maxHeaderValueLength > 0, "max-header-value-length must be > 0")
  require(maxHeaderCount > 0, "max-header-count must be > 0")
  require(maxContentLength > 0, "max-content-length must be > 0")
  require(maxChunkExtLength > 0, "max-chunk-ext-length must be > 0")
  require(maxChunkSize > 0, "max-chunk-size must be > 0")

  val defaultHeaderValueCacheLimit: Int = headerValueCacheLimits("default")

  def headerValueCacheLimit(headerName: String) =
    headerValueCacheLimits.getOrElse(headerName, defaultHeaderValueCacheLimit)
}

object ParserSettings extends SettingsCompanion[ParserSettings]("akka.http.parsing") {
  def fromSubConfig(c: Config) = {
    val cacheConfig = c getConfig "header-cache"

    apply(
      c getIntBytes "max-uri-length",
      c getIntBytes "max-response-reason-length",
      c getIntBytes "max-header-name-length",
      c getIntBytes "max-header-value-length",
      c getIntBytes "max-header-count",
      c getBytes "max-content-length",
      c getIntBytes "max-chunk-ext-length",
      c getIntBytes "max-chunk-size",
      Uri.ParsingMode(c getString "uri-parsing-mode"),
      c getBoolean "illegal-header-warnings",
      c getBoolean "ssl-session-info-header",
      cacheConfig.entrySet.asScala.map(kvp ⇒ kvp.getKey -> cacheConfig.getInt(kvp.getKey))(collection.breakOut))
  }
}

