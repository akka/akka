/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.stm

import java.lang.{ Boolean ⇒ JBoolean }

import akka.util.Duration

import org.multiverse.api.GlobalStmInstance.getGlobalStmInstance
import org.multiverse.stms.alpha.AlphaStm
import org.multiverse.templates.TransactionBoilerplate
import org.multiverse.api.PropagationLevel
import org.multiverse.api.{ TraceLevel ⇒ MTraceLevel }

/**
 * For configuring multiverse transactions.
 */
object TransactionConfig {
  object Default {
    // note: null values are so that we can default to Multiverse inference when not set
    val FamilyName = "DefaultTransaction"
    val Readonly = null.asInstanceOf[JBoolean]
    val MaxRetries = 1000
    val Timeout = Duration(5, "seconds")
    val TrackReads = null.asInstanceOf[JBoolean]
    val WriteSkew = true
    val BlockingAllowed = false
    val Interruptible = false
    val Speculative = true
    val QuickRelease = true
    val Propagation = PropagationLevel.Requires
    val TraceLevel = MTraceLevel.none
  }

  /**
   * For configuring multiverse transactions.
   *
   * @param familyName       Family name for transactions. Useful for debugging.
   * @param readonly         Sets transaction as readonly. Readonly transactions are cheaper.
   * @param maxRetries       The maximum number of times a transaction will retry.
   * @param timeout          The maximum time a transaction will block for.
   * @param trackReads       Whether all reads should be tracked. Needed for blocking operations.
   * @param writeSkew        Whether writeskew is allowed. Disable with care.
   * @param blockingAllowed  Whether explicit retries are allowed.
   * @param interruptible    Whether a blocking transaction can be interrupted.
   * @param speculative      Whether speculative configuration should be enabled.
   * @param quickRelease     Whether locks should be released as quickly as possible (before whole commit).
   * @param propagation      For controlling how nested transactions behave.
   * @param traceLevel       Transaction trace level.
   */
  def apply(familyName: String = Default.FamilyName,
            readonly: JBoolean = Default.Readonly,
            maxRetries: Int = Default.MaxRetries,
            timeout: Duration = Default.Timeout,
            trackReads: JBoolean = Default.TrackReads,
            writeSkew: Boolean = Default.WriteSkew,
            blockingAllowed: Boolean = Default.BlockingAllowed,
            interruptible: Boolean = Default.Interruptible,
            speculative: Boolean = Default.Speculative,
            quickRelease: Boolean = Default.QuickRelease,
            propagation: PropagationLevel = Default.Propagation,
            traceLevel: MTraceLevel = Default.TraceLevel) = {
    new TransactionConfig(familyName, readonly, maxRetries, timeout, trackReads, writeSkew, blockingAllowed,
      interruptible, speculative, quickRelease, propagation, traceLevel)
  }
}

/**
 * For configuring multiverse transactions.
 *
 * <p>familyName      - Family name for transactions. Useful for debugging.
 * <p>readonly        - Sets transaction as readonly. Readonly transactions are cheaper.
 * <p>maxRetries      - The maximum number of times a transaction will retry.
 * <p>timeout         - The maximum time a transaction will block for.
 * <p>trackReads      - Whether all reads should be tracked. Needed for blocking operations.
 * <p>writeSkew       - Whether writeskew is allowed. Disable with care.
 * <p>blockingAllowed - Whether explicit retries are allowed.
 * <p>interruptible   - Whether a blocking transaction can be interrupted.
 * <p>speculative     - Whether speculative configuration should be enabled.
 * <p>quickRelease    - Whether locks should be released as quickly as possible (before whole commit).
 * <p>propagation     - For controlling how nested transactions behave.
 * <p>traceLevel      - Transaction trace level.
 */
class TransactionConfig(val familyName: String = TransactionConfig.Default.FamilyName,
                        val readonly: JBoolean = TransactionConfig.Default.Readonly,
                        val maxRetries: Int = TransactionConfig.Default.MaxRetries,
                        val timeout: Duration = TransactionConfig.Default.Timeout,
                        val trackReads: JBoolean = TransactionConfig.Default.TrackReads,
                        val writeSkew: Boolean = TransactionConfig.Default.WriteSkew,
                        val blockingAllowed: Boolean = TransactionConfig.Default.BlockingAllowed,
                        val interruptible: Boolean = TransactionConfig.Default.Interruptible,
                        val speculative: Boolean = TransactionConfig.Default.Speculative,
                        val quickRelease: Boolean = TransactionConfig.Default.QuickRelease,
                        val propagation: PropagationLevel = TransactionConfig.Default.Propagation,
                        val traceLevel: MTraceLevel = TransactionConfig.Default.TraceLevel)

object DefaultTransactionConfig extends TransactionConfig

/**
 * Wrapper for transaction config, factory, and boilerplate. Used by atomic.
 */
object TransactionFactory {
  def apply(config: TransactionConfig) = new TransactionFactory(config)

  def apply(config: TransactionConfig, defaultName: String) = new TransactionFactory(config, defaultName)

  def apply(familyName: String = TransactionConfig.Default.FamilyName,
            readonly: JBoolean = TransactionConfig.Default.Readonly,
            maxRetries: Int = TransactionConfig.Default.MaxRetries,
            timeout: Duration = TransactionConfig.Default.Timeout,
            trackReads: JBoolean = TransactionConfig.Default.TrackReads,
            writeSkew: Boolean = TransactionConfig.Default.WriteSkew,
            blockingAllowed: Boolean = TransactionConfig.Default.BlockingAllowed,
            interruptible: Boolean = TransactionConfig.Default.Interruptible,
            speculative: Boolean = TransactionConfig.Default.Speculative,
            quickRelease: Boolean = TransactionConfig.Default.QuickRelease,
            propagation: PropagationLevel = TransactionConfig.Default.Propagation,
            traceLevel: MTraceLevel = TransactionConfig.Default.TraceLevel) = {
    val config = new TransactionConfig(
      familyName, readonly, maxRetries, timeout, trackReads, writeSkew, blockingAllowed,
      interruptible, speculative, quickRelease, propagation, traceLevel)
    new TransactionFactory(config)
  }
}

/**
 * Wrapper for transaction config, factory, and boilerplate. Used by atomic.
 * Can be passed to atomic implicitly or explicitly.
 *
 * {{{
 * implicit val txFactory = TransactionFactory(readonly = true)
 * ...
 * atomic {
 *   // do something within a readonly transaction
 * }
 * }}}
 *
 * Can be created at different levels as needed. For example: as an implicit object
 * used throughout a package, as a static implicit val within a singleton object and
 * imported where needed, or as an implicit val within each instance of a class.
 *
 * If no explicit transaction factory is passed to atomic and there is no implicit
 * transaction factory in scope, then a default transaction factory is used.
 *
 * @see [[akka.stm.TransactionConfig]] for configuration options.
 */
class TransactionFactory(
  val config: TransactionConfig = DefaultTransactionConfig,
  defaultName: String = TransactionConfig.Default.FamilyName) { self ⇒

  // use the config family name if it's been set, otherwise defaultName - used by actors to set class name as default
  val familyName = if (config.familyName != TransactionConfig.Default.FamilyName) config.familyName else defaultName

  val factory = {
    var builder = (getGlobalStmInstance().asInstanceOf[AlphaStm].getTransactionFactoryBuilder()
      .setFamilyName(familyName)
      .setMaxRetries(config.maxRetries)
      .setTimeoutNs(config.timeout.toNanos)
      .setWriteSkewAllowed(config.writeSkew)
      .setExplicitRetryAllowed(config.blockingAllowed)
      .setInterruptible(config.interruptible)
      .setSpeculativeConfigurationEnabled(config.speculative)
      .setQuickReleaseEnabled(config.quickRelease)
      .setPropagationLevel(config.propagation)
      .setTraceLevel(config.traceLevel))

    if (config.readonly ne null) {
      builder = builder.setReadonly(config.readonly.booleanValue)
    } // otherwise default to Multiverse inference

    if (config.trackReads ne null) {
      builder = builder.setReadTrackingEnabled(config.trackReads.booleanValue)
    } // otherwise default to Multiverse inference

    builder.build()
  }

  val boilerplate = new TransactionBoilerplate(factory)
}

/**
 * Mapping to Multiverse PropagationLevel.
 */
object Propagation {
  val RequiresNew = PropagationLevel.RequiresNew
  val Mandatory = PropagationLevel.Mandatory
  val Requires = PropagationLevel.Requires
  val Supports = PropagationLevel.Supports
  val Never = PropagationLevel.Never
}

/**
 * Mapping to Multiverse TraceLevel.
 */
object TraceLevel {
  val None = MTraceLevel.none
  val Coarse = MTraceLevel.course // mispelling?
  val Course = MTraceLevel.course
  val Fine = MTraceLevel.fine
}
