/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.stm

import java.lang.{ Boolean ⇒ JBoolean }

import akka.util.Duration

import org.multiverse.api.{ TraceLevel ⇒ MTraceLevel }
import org.multiverse.api.{ PropagationLevel ⇒ MPropagation }

/**
 * For more easily creating TransactionConfig from Java.
 */
class TransactionConfigBuilder {
  var familyName: String = TransactionConfig.FAMILY_NAME
  var readonly: JBoolean = TransactionConfig.READONLY
  var maxRetries: Int = TransactionConfig.MAX_RETRIES
  var timeout: Duration = TransactionConfig.DefaultTimeout
  var trackReads: JBoolean = TransactionConfig.TRACK_READS
  var writeSkew: Boolean = TransactionConfig.WRITE_SKEW
  var blockingAllowed: Boolean = TransactionConfig.BLOCKING_ALLOWED
  var interruptible: Boolean = TransactionConfig.INTERRUPTIBLE
  var speculative: Boolean = TransactionConfig.SPECULATIVE
  var quickRelease: Boolean = TransactionConfig.QUICK_RELEASE
  var propagation: MPropagation = TransactionConfig.PROPAGATION
  var traceLevel: MTraceLevel = TransactionConfig.TRACE_LEVEL

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
  def setPropagation(propagation: MPropagation) = { this.propagation = propagation; this }
  def setTraceLevel(traceLevel: MTraceLevel) = { this.traceLevel = traceLevel; this }

  def build() = new TransactionConfig(
    familyName, readonly, maxRetries, timeout, trackReads, writeSkew, blockingAllowed,
    interruptible, speculative, quickRelease, propagation, traceLevel)
}

/**
 * For more easily creating TransactionFactory from Java.
 */
class TransactionFactoryBuilder {
  var familyName: String = TransactionConfig.FAMILY_NAME
  var readonly: JBoolean = TransactionConfig.READONLY
  var maxRetries: Int = TransactionConfig.MAX_RETRIES
  var timeout: Duration = TransactionConfig.DefaultTimeout
  var trackReads: JBoolean = TransactionConfig.TRACK_READS
  var writeSkew: Boolean = TransactionConfig.WRITE_SKEW
  var blockingAllowed: Boolean = TransactionConfig.BLOCKING_ALLOWED
  var interruptible: Boolean = TransactionConfig.INTERRUPTIBLE
  var speculative: Boolean = TransactionConfig.SPECULATIVE
  var quickRelease: Boolean = TransactionConfig.QUICK_RELEASE
  var propagation: MPropagation = TransactionConfig.PROPAGATION
  var traceLevel: MTraceLevel = TransactionConfig.TRACE_LEVEL

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
  def setPropagation(propagation: MPropagation) = { this.propagation = propagation; this }
  def setTraceLevel(traceLevel: MTraceLevel) = { this.traceLevel = traceLevel; this }

  def build() = {
    val config = new TransactionConfig(
      familyName, readonly, maxRetries, timeout, trackReads, writeSkew, blockingAllowed,
      interruptible, speculative, quickRelease, propagation, traceLevel)
    new TransactionFactory(config)
  }
}
