/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.stm

import java.lang.{ Boolean ⇒ JBoolean }

import akka.util.Duration

import org.multiverse.api.{ TraceLevel ⇒ MTraceLevel }
import org.multiverse.api.PropagationLevel

/**
 * For more easily creating TransactionConfig from Java.
 */
class TransactionConfigBuilder {
  var familyName: String = TransactionConfig.Default.FamilyName
  var readonly: JBoolean = TransactionConfig.Default.Readonly
  var maxRetries: Int = TransactionConfig.Default.MaxRetries
  var timeout: Duration = TransactionConfig.Default.Timeout
  var trackReads: JBoolean = TransactionConfig.Default.TrackReads
  var writeSkew: Boolean = TransactionConfig.Default.WriteSkew
  var blockingAllowed: Boolean = TransactionConfig.Default.BlockingAllowed
  var interruptible: Boolean = TransactionConfig.Default.Interruptible
  var speculative: Boolean = TransactionConfig.Default.Speculative
  var quickRelease: Boolean = TransactionConfig.Default.QuickRelease
  var propagation: PropagationLevel = TransactionConfig.Default.Propagation
  var traceLevel: MTraceLevel = TransactionConfig.Default.TraceLevel

  def setFamilyName(familyName: String) = { this.familyName = familyName; this }
  def setReadonly(readonly: JBoolean) = { this.readonly = readonly; this }
  def setMaxRetries(maxRetries: Int) = { this.maxRetries = maxRetries; this }
  def setTimeout(timeout: Duration) = { this.timeout = timeout; this }
  def setTrackReads(trackReads: JBoolean) = { this.trackReads = trackReads; this }
  def setWriteSkew(writeSkew: Boolean) = { this.writeSkew = writeSkew; this }
  def setBlockingAllowed(blockingAllowed: Boolean) = { this.blockingAllowed = blockingAllowed; this }
  def setInterruptible(interruptible: Boolean) = { this.interruptible = interruptible; this }
  def setSpeculative(speculative: Boolean) = { this.speculative = speculative; this }
  def setQuickRelease(quickRelease: Boolean) = { this.quickRelease = quickRelease; this }
  def setPropagation(propagation: PropagationLevel) = { this.propagation = propagation; this }
  def setTraceLevel(traceLevel: MTraceLevel) = { this.traceLevel = traceLevel; this }

  def build() = new TransactionConfig(
    familyName, readonly, maxRetries, timeout, trackReads, writeSkew, blockingAllowed,
    interruptible, speculative, quickRelease, propagation, traceLevel)
}

/**
 * For more easily creating TransactionFactory from Java.
 */
class TransactionFactoryBuilder {
  var familyName: String = TransactionConfig.Default.FamilyName
  var readonly: JBoolean = TransactionConfig.Default.Readonly
  var maxRetries: Int = TransactionConfig.Default.MaxRetries
  var timeout: Duration = TransactionConfig.Default.Timeout
  var trackReads: JBoolean = TransactionConfig.Default.TrackReads
  var writeSkew: Boolean = TransactionConfig.Default.WriteSkew
  var blockingAllowed: Boolean = TransactionConfig.Default.BlockingAllowed
  var interruptible: Boolean = TransactionConfig.Default.Interruptible
  var speculative: Boolean = TransactionConfig.Default.Speculative
  var quickRelease: Boolean = TransactionConfig.Default.QuickRelease
  var propagation: PropagationLevel = TransactionConfig.Default.Propagation
  var traceLevel: MTraceLevel = TransactionConfig.Default.TraceLevel

  def setFamilyName(familyName: String) = { this.familyName = familyName; this }
  def setReadonly(readonly: JBoolean) = { this.readonly = readonly; this }
  def setMaxRetries(maxRetries: Int) = { this.maxRetries = maxRetries; this }
  def setTimeout(timeout: Duration) = { this.timeout = timeout; this }
  def setTrackReads(trackReads: JBoolean) = { this.trackReads = trackReads; this }
  def setWriteSkew(writeSkew: Boolean) = { this.writeSkew = writeSkew; this }
  def setBlockingAllowed(blockingAllowed: Boolean) = { this.blockingAllowed = blockingAllowed; this }
  def setInterruptible(interruptible: Boolean) = { this.interruptible = interruptible; this }
  def setSpeculative(speculative: Boolean) = { this.speculative = speculative; this }
  def setQuickRelease(quickRelease: Boolean) = { this.quickRelease = quickRelease; this }
  def setPropagation(propagation: PropagationLevel) = { this.propagation = propagation; this }
  def setTraceLevel(traceLevel: MTraceLevel) = { this.traceLevel = traceLevel; this }

  def build() = {
    val config = new TransactionConfig(
      familyName, readonly, maxRetries, timeout, trackReads, writeSkew, blockingAllowed,
      interruptible, speculative, quickRelease, propagation, traceLevel)
    new TransactionFactory(config)
  }
}
