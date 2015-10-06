/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.server

import com.typesafe.config.Config
import akka.actor.ActorRefFactory
import akka.http.impl.util._

case class RoutingSettings(
  verboseErrorMessages: Boolean,
  fileGetConditional: Boolean,
  renderVanityFooter: Boolean,
  rangeCountLimit: Int,
  rangeCoalescingThreshold: Long,
  decodeMaxBytesPerChunk: Int,
  fileIODispatcher: String)

object RoutingSettings extends SettingsCompanion[RoutingSettings]("akka.http.routing") {
  def fromSubConfig(root: Config, c: Config) = apply(
    c getBoolean "verbose-error-messages",
    c getBoolean "file-get-conditional",
    c getBoolean "render-vanity-footer",
    c getInt "range-count-limit",
    c getBytes "range-coalescing-threshold",
    c getIntBytes "decode-max-bytes-per-chunk",
    c getString "file-io-dispatcher")

  implicit def default(implicit refFactory: ActorRefFactory) =
    apply(actorSystem)
}
