/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.stm

import java.lang.{ Boolean ⇒ JBoolean }

import akka.config.Config._
import akka.util.Duration

import org.multiverse.api.GlobalStmInstance.getGlobalStmInstance
import org.multiverse.stms.alpha.AlphaStm
import org.multiverse.templates.TransactionBoilerplate
import org.multiverse.api.{ PropagationLevel ⇒ MPropagation }
import org.multiverse.api.{ TraceLevel ⇒ MTraceLevel }

/**
 * For configuring multiverse transactions.
 */
object TransactionConfig {
  // note: null values are so that we can default to Multiverse inference when not set
  val FAMILY_NAME = "DefaultTransaction"
  val READONLY = null.asInstanceOf[JBoolean]
  val MAX_RETRIES = config.getInt("akka.stm.max-retries", 1000)
  val TIMEOUT = config.getLong("akka.stm.timeout", 5)
  val TRACK_READS = null.asInstanceOf[JBoolean]
  val WRITE_SKEW = config.getBool("akka.stm.write-skew", true)
  val BLOCKING_ALLOWED = config.getBool("akka.stm.blocking-allowed", false)
  val INTERRUPTIBLE = config.getBool("akka.stm.interruptible", false)
  val SPECULATIVE = config.getBool("akka.stm.speculative", true)
  val QUICK_RELEASE = config.getBool("akka.stm.quick-release", true)
  val PROPAGATION = propagation(config.getString("akka.stm.propagation", "requires"))
  val TRACE_LEVEL = traceLevel(config.getString("akka.stm.trace-level", "none"))

  val DefaultTimeout = Duration(TIMEOUT, TIME_UNIT)

  def propagation(level: String) = level.toLowerCase match {
    case "requiresnew" ⇒ Propagation.RequiresNew
    case "fine"        ⇒ Propagation.Mandatory
    case "supports"    ⇒ Propagation.Supports
    case "never"       ⇒ Propagation.Never
    case _             ⇒ Propagation.Requires
  }

  def traceLevel(level: String) = level.toLowerCase match {
    case "coarse" | "course" ⇒ TraceLevel.Coarse
    case "fine"              ⇒ TraceLevel.Fine
    case _                   ⇒ TraceLevel.None
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
  def apply(familyName: String = FAMILY_NAME,
            readonly: JBoolean = READONLY,
            maxRetries: Int = MAX_RETRIES,
            timeout: Duration = DefaultTimeout,
            trackReads: JBoolean = TRACK_READS,
            writeSkew: Boolean = WRITE_SKEW,
            blockingAllowed: Boolean = BLOCKING_ALLOWED,
            interruptible: Boolean = INTERRUPTIBLE,
            speculative: Boolean = SPECULATIVE,
            quickRelease: Boolean = QUICK_RELEASE,
            propagation: MPropagation = PROPAGATION,
            traceLevel: MTraceLevel = TRACE_LEVEL) = {
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
class TransactionConfig(val familyName: String = TransactionConfig.FAMILY_NAME,
                        val readonly: JBoolean = TransactionConfig.READONLY,
                        val maxRetries: Int = TransactionConfig.MAX_RETRIES,
                        val timeout: Duration = TransactionConfig.DefaultTimeout,
                        val trackReads: JBoolean = TransactionConfig.TRACK_READS,
                        val writeSkew: Boolean = TransactionConfig.WRITE_SKEW,
                        val blockingAllowed: Boolean = TransactionConfig.BLOCKING_ALLOWED,
                        val interruptible: Boolean = TransactionConfig.INTERRUPTIBLE,
                        val speculative: Boolean = TransactionConfig.SPECULATIVE,
                        val quickRelease: Boolean = TransactionConfig.QUICK_RELEASE,
                        val propagation: MPropagation = TransactionConfig.PROPAGATION,
                        val traceLevel: MTraceLevel = TransactionConfig.TRACE_LEVEL)

object DefaultTransactionConfig extends TransactionConfig

/**
 * Wrapper for transaction config, factory, and boilerplate. Used by atomic.
 */
object TransactionFactory {
  def apply(config: TransactionConfig) = new TransactionFactory(config)

  def apply(config: TransactionConfig, defaultName: String) = new TransactionFactory(config, defaultName)

  def apply(familyName: String = TransactionConfig.FAMILY_NAME,
            readonly: JBoolean = TransactionConfig.READONLY,
            maxRetries: Int = TransactionConfig.MAX_RETRIES,
            timeout: Duration = TransactionConfig.DefaultTimeout,
            trackReads: JBoolean = TransactionConfig.TRACK_READS,
            writeSkew: Boolean = TransactionConfig.WRITE_SKEW,
            blockingAllowed: Boolean = TransactionConfig.BLOCKING_ALLOWED,
            interruptible: Boolean = TransactionConfig.INTERRUPTIBLE,
            speculative: Boolean = TransactionConfig.SPECULATIVE,
            quickRelease: Boolean = TransactionConfig.QUICK_RELEASE,
            propagation: MPropagation = TransactionConfig.PROPAGATION,
            traceLevel: MTraceLevel = TransactionConfig.TRACE_LEVEL) = {
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
  defaultName: String = TransactionConfig.FAMILY_NAME) { self ⇒

  // use the config family name if it's been set, otherwise defaultName - used by actors to set class name as default
  val familyName = if (config.familyName != TransactionConfig.FAMILY_NAME) config.familyName else defaultName

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
  val RequiresNew = MPropagation.RequiresNew
  val Mandatory = MPropagation.Mandatory
  val Requires = MPropagation.Requires
  val Supports = MPropagation.Supports
  val Never = MPropagation.Never
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
