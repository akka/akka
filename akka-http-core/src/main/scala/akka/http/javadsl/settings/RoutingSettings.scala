/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.settings

import akka.http.impl.settings.RoutingSettingsImpl
import com.typesafe.config.Config

/**
 * Public API but not intended for subclassing
 */
abstract class RoutingSettings private[akka] () { self: RoutingSettingsImpl ⇒
  def getVerboseErrorMessages: Boolean
  def getFileGetConditional: Boolean
  def getRenderVanityFooter: Boolean
  def getRangeCountLimit: Int
  def getRangeCoalescingThreshold: Long
  def getDecodeMaxBytesPerChunk: Int
  def getFileIODispatcher: String

  def withVerboseErrorMessages(verboseErrorMessages: Boolean): RoutingSettings = self.copy(verboseErrorMessages = verboseErrorMessages)
  def withFileGetConditional(fileGetConditional: Boolean): RoutingSettings = self.copy(fileGetConditional = fileGetConditional)
  def withRenderVanityFooter(renderVanityFooter: Boolean): RoutingSettings = self.copy(renderVanityFooter = renderVanityFooter)
  def withRangeCountLimit(rangeCountLimit: Int): RoutingSettings = self.copy(rangeCountLimit = rangeCountLimit)
  def withRangeCoalescingThreshold(rangeCoalescingThreshold: Long): RoutingSettings = self.copy(rangeCoalescingThreshold = rangeCoalescingThreshold)
  def withDecodeMaxBytesPerChunk(decodeMaxBytesPerChunk: Int): RoutingSettings = self.copy(decodeMaxBytesPerChunk = decodeMaxBytesPerChunk)
  def withFileIODispatcher(fileIODispatcher: String): RoutingSettings = self.copy(fileIODispatcher = fileIODispatcher)
}

object RoutingSettings extends SettingsCompanion[RoutingSettings] {
  override def create(config: Config): RoutingSettings = RoutingSettingsImpl(config)
  override def create(configOverrides: String): RoutingSettings = RoutingSettingsImpl(configOverrides)
}
