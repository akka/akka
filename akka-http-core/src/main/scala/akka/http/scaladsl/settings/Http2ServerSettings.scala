/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.settings

import akka.annotation.{ ApiMayChange, InternalApi }
import akka.http.javadsl
import akka.http.impl.util._
import com.typesafe.config.Config

/**
 * Placeholder for any kind of internal settings that might be interesting for HTTP/2 (like custom strategies)
 */
@InternalApi
private[http] trait Http2InternalServerSettings

@ApiMayChange
trait Http2ServerSettings extends javadsl.settings.Http2ServerSettings { self: Http2ServerSettings.Http2ServerSettingsImpl ⇒
  def requestEntityChunkSize: Int
  def withRequestEntityChunkSize(newValue: Int): Http2ServerSettings =
    copy(requestEntityChunkSize = newValue)

  def incomingConnectionLevelBufferSize: Int
  def withIncomingConnectionLevelBufferSize(newValue: Int): Http2ServerSettings =
    copy(incomingConnectionLevelBufferSize = newValue)

  def incomingStreamLevelBufferSize: Int
  def withIncomingStreamLevelBufferSize(newValue: Int): Http2ServerSettings =
    copy(incomingStreamLevelBufferSize = newValue)

  @InternalApi
  private[http] def internalSettings: Option[Http2InternalServerSettings]
  @InternalApi
  private[http] def withInternalSettings(newValue: Http2InternalServerSettings): Http2ServerSettings =
    copy(internalSettings = Some(newValue))
}

@ApiMayChange
object Http2ServerSettings extends SettingsCompanion[Http2ServerSettings] {
  def apply(config: Config): Http2ServerSettings = Http2ServerSettingsImpl(config)
  def apply(configOverrides: String): Http2ServerSettings = Http2ServerSettingsImpl(configOverrides)

  private[http] case class Http2ServerSettingsImpl(
    requestEntityChunkSize:            Int,
    incomingConnectionLevelBufferSize: Int,
    incomingStreamLevelBufferSize:     Int,
    internalSettings:                  Option[Http2InternalServerSettings])
    extends Http2ServerSettings {
    require(requestEntityChunkSize > 0, "request-entity-chunk-size must be > 0")
    require(incomingConnectionLevelBufferSize > 0, "incoming-connection-level-buffer-size must be > 0")
    require(incomingStreamLevelBufferSize > 0, "incoming-stream-level-buffer-size must be > 0")
  }

  private[http] object Http2ServerSettingsImpl extends akka.http.impl.util.SettingsCompanion[Http2ServerSettingsImpl]("akka.http.server.http2") {
    def fromSubConfig(root: Config, c: Config): Http2ServerSettingsImpl = Http2ServerSettingsImpl(
      requestEntityChunkSize = c getIntBytes "request-entity-chunk-size",
      incomingConnectionLevelBufferSize = c getIntBytes "incoming-connection-level-buffer-size",
      incomingStreamLevelBufferSize = c getIntBytes "incoming-stream-level-buffer-size",
      None // no possibility to configure internal settings with config
    )
  }
}