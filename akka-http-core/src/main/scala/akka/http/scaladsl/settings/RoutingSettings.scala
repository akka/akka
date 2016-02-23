/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.scaladsl.settings

import akka.http.impl.settings.RoutingSettingsImpl
import com.typesafe.config.Config

/**
 * Public API but not intended for subclassing
 */
abstract class RoutingSettings private[akka] () extends akka.http.javadsl.settings.RoutingSettings { self: RoutingSettingsImpl ⇒
  def verboseErrorMessages: Boolean
  def fileGetConditional: Boolean
  def renderVanityFooter: Boolean
  def rangeCountLimit: Int
  def rangeCoalescingThreshold: Long
  def decodeMaxBytesPerChunk: Int
  def fileIODispatcher: String

  /* Java APIs */
  def getVerboseErrorMessages: Boolean = verboseErrorMessages
  def getFileGetConditional: Boolean = fileGetConditional
  def getRenderVanityFooter: Boolean = renderVanityFooter
  def getRangeCountLimit: Int = rangeCountLimit
  def getRangeCoalescingThreshold: Long = rangeCoalescingThreshold
  def getDecodeMaxBytesPerChunk: Int = decodeMaxBytesPerChunk
  def getFileIODispatcher: String = fileIODispatcher

  override def withVerboseErrorMessages(verboseErrorMessages: Boolean): RoutingSettings = self.copy(verboseErrorMessages = verboseErrorMessages)
  override def withFileGetConditional(fileGetConditional: Boolean): RoutingSettings = self.copy(fileGetConditional = fileGetConditional)
  override def withRenderVanityFooter(renderVanityFooter: Boolean): RoutingSettings = self.copy(renderVanityFooter = renderVanityFooter)
  override def withRangeCountLimit(rangeCountLimit: Int): RoutingSettings = self.copy(rangeCountLimit = rangeCountLimit)
  override def withRangeCoalescingThreshold(rangeCoalescingThreshold: Long): RoutingSettings = self.copy(rangeCoalescingThreshold = rangeCoalescingThreshold)
  override def withDecodeMaxBytesPerChunk(decodeMaxBytesPerChunk: Int): RoutingSettings = self.copy(decodeMaxBytesPerChunk = decodeMaxBytesPerChunk)
  override def withFileIODispatcher(fileIODispatcher: String): RoutingSettings = self.copy(fileIODispatcher = fileIODispatcher)

}

object RoutingSettings extends SettingsCompanion[RoutingSettings] {
  override def apply(config: Config): RoutingSettings = RoutingSettingsImpl(config)
  override def apply(configOverrides: String): RoutingSettings = RoutingSettingsImpl(configOverrides)
}
