/*
 * Copyright (C) 2009-2014 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.settings

import akka.http.impl.util._
import com.typesafe.config.Config

/** INTERNAL API */
final case class RoutingSettingsImpl(
  verboseErrorMessages:     Boolean,
  fileGetConditional:       Boolean,
  renderVanityFooter:       Boolean,
  rangeCountLimit:          Int,
  rangeCoalescingThreshold: Long,
  decodeMaxBytesPerChunk:   Int,
  fileIODispatcher:         String) extends akka.http.scaladsl.settings.RoutingSettings {

  override def productPrefix = "RoutingSettings"
}

object RoutingSettingsImpl extends SettingsCompanion[RoutingSettingsImpl]("akka.http.routing") {
  def fromSubConfig(root: Config, c: Config) = new RoutingSettingsImpl(
    c getBoolean "verbose-error-messages",
    c getBoolean "file-get-conditional",
    c getBoolean "render-vanity-footer",
    c getInt "range-count-limit",
    c getBytes "range-coalescing-threshold",
    c getIntBytes "decode-max-bytes-per-chunk",
    c getString "file-io-dispatcher")

}
