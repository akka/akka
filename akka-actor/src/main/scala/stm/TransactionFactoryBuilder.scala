/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.stm

import java.lang.{Boolean => JBoolean}

import se.scalablesolutions.akka.util.Duration

import org.multiverse.api.TraceLevel
import org.multiverse.api.{PropagationLevel => Propagation}

/**
 * For more easily creating TransactionConfig from Java.
 */
class TransactionConfigBuilder {
  var familyName: String       = TransactionConfig.FAMILY_NAME
  var readonly: JBoolean       = TransactionConfig.READONLY
  var maxRetries: Int          = TransactionConfig.MAX_RETRIES
  var timeout: Duration        = TransactionConfig.DefaultTimeout
  var trackReads: JBoolean     = TransactionConfig.TRACK_READS
  var writeSkew: Boolean       = TransactionConfig.WRITE_SKEW
  var blockingAllowed: Boolean = TransactionConfig.BLOCKING_ALLOWED
  var interruptible: Boolean   = TransactionConfig.INTERRUPTIBLE
  var speculative: Boolean     = TransactionConfig.SPECULATIVE
  var quickRelease: Boolean    = TransactionConfig.QUICK_RELEASE
  var propagation: Propagation = TransactionConfig.PROPAGATION
  var traceLevel: TraceLevel   = TransactionConfig.TRACE_LEVEL
  var hooks: Boolean           = TransactionConfig.HOOKS

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
  def setPropagation(propagation: Propagation) = { this.propagation = propagation; this }
  def setTraceLevel(traceLevel: TraceLevel) = { this.traceLevel = traceLevel; this }
  def setHooks(hooks: Boolean) = { this.hooks = hooks; this }

  def build() = new TransactionConfig(
    familyName, readonly, maxRetries, timeout, trackReads, writeSkew, blockingAllowed,
    interruptible, speculative, quickRelease, propagation, traceLevel, hooks)
}

/**
 * For more easily creating TransactionFactory from Java.
 */
class TransactionFactoryBuilder {
  var familyName: String       = TransactionConfig.FAMILY_NAME
  var readonly: JBoolean       = TransactionConfig.READONLY
  var maxRetries: Int          = TransactionConfig.MAX_RETRIES
  var timeout: Duration        = TransactionConfig.DefaultTimeout
  var trackReads: JBoolean     = TransactionConfig.TRACK_READS
  var writeSkew: Boolean       = TransactionConfig.WRITE_SKEW
  var blockingAllowed: Boolean = TransactionConfig.BLOCKING_ALLOWED
  var interruptible: Boolean   = TransactionConfig.INTERRUPTIBLE
  var speculative: Boolean     = TransactionConfig.SPECULATIVE
  var quickRelease: Boolean    = TransactionConfig.QUICK_RELEASE
  var propagation: Propagation = TransactionConfig.PROPAGATION
  var traceLevel: TraceLevel   = TransactionConfig.TRACE_LEVEL
  var hooks: Boolean           = TransactionConfig.HOOKS

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
  def setPropagation(propagation: Propagation) = { this.propagation = propagation; this }
  def setTraceLevel(traceLevel: TraceLevel) = { this.traceLevel = traceLevel; this }
  def setHooks(hooks: Boolean) = { this.hooks = hooks; this }

  def build() = {
    val config = new TransactionConfig(
      familyName, readonly, maxRetries, timeout, trackReads, writeSkew, blockingAllowed,
      interruptible, speculative, quickRelease, propagation, traceLevel, hooks)
    new TransactionFactory(config)
  }
}
