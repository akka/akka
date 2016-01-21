/*
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.server

import com.typesafe.config.Config
import akka.actor.ActorRefFactory
import akka.http.impl.util._

final class RoutingSettings(
  val verboseErrorMessages: Boolean,
  val fileGetConditional: Boolean,
  val renderVanityFooter: Boolean,
  val rangeCountLimit: Int,
  val rangeCoalescingThreshold: Long,
  val decodeMaxBytesPerChunk: Int,
  val fileIODispatcher: String) {

  def copy(
    verboseErrorMessages: Boolean = verboseErrorMessages,
    fileGetConditional: Boolean = fileGetConditional,
    renderVanityFooter: Boolean = renderVanityFooter,
    rangeCountLimit: Int = rangeCountLimit,
    rangeCoalescingThreshold: Long = rangeCoalescingThreshold,
    decodeMaxBytesPerChunk: Int = decodeMaxBytesPerChunk,
    fileIODispatcher: String = fileIODispatcher): RoutingSettings =
    new RoutingSettings(
      verboseErrorMessages,
      fileGetConditional,
      renderVanityFooter,
      rangeCountLimit,
      rangeCoalescingThreshold,
      decodeMaxBytesPerChunk,
      fileIODispatcher)

  // TODO we should automate generating those
  override def toString = {
    getClass.getSimpleName + "(" +
      verboseErrorMessages + "," +
      fileGetConditional + "," +
      renderVanityFooter + "," +
      rangeCountLimit + "," +
      rangeCoalescingThreshold + "," +
      decodeMaxBytesPerChunk + "," +
      fileIODispatcher +
      ")"
  }
}

object RoutingSettings extends SettingsCompanion[RoutingSettings]("akka.http.routing") {
  def fromSubConfig(root: Config, c: Config) = new RoutingSettings(
    c getBoolean "verbose-error-messages",
    c getBoolean "file-get-conditional",
    c getBoolean "render-vanity-footer",
    c getInt "range-count-limit",
    c getBytes "range-coalescing-threshold",
    c getIntBytes "decode-max-bytes-per-chunk",
    c getString "file-io-dispatcher")

  implicit def default(implicit refFactory: ActorRefFactory): RoutingSettings =
    apply(actorSystem)

  def apply(
    verboseErrorMessages: Boolean,
    fileGetConditional: Boolean,
    renderVanityFooter: Boolean,
    rangeCountLimit: Int,
    rangeCoalescingThreshold: Long,
    decodeMaxBytesPerChunk: Int,
    fileIODispatcher: String): RoutingSettings =
    new RoutingSettings(
      verboseErrorMessages,
      fileGetConditional,
      renderVanityFooter,
      rangeCountLimit,
      rangeCoalescingThreshold,
      decodeMaxBytesPerChunk,
      fileIODispatcher)
}
