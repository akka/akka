/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.stm

import java.lang.{Boolean => JBoolean}

import se.scalablesolutions.akka.config.Config._
import se.scalablesolutions.akka.util.Duration

import org.multiverse.api.GlobalStmInstance.getGlobalStmInstance
import org.multiverse.stms.alpha.AlphaStm
import org.multiverse.templates.TransactionBoilerplate
import org.multiverse.api.TraceLevel

/**
 * For configuring multiverse transactions.
 */
object TransactionConfig {
  // note: null values are so that we can default to Multiverse inference when not set
  val FAMILY_NAME      = "DefaultTransaction"
  val READONLY         = null.asInstanceOf[JBoolean]
  val MAX_RETRIES      = config.getInt("akka.stm.max-retries", 1000)
  val TIMEOUT          = config.getLong("akka.stm.timeout", 10)
  val TIME_UNIT        = config.getString("akka.stm.time-unit", "seconds")
  val TRACK_READS      = null.asInstanceOf[JBoolean]
  val WRITE_SKEW       = config.getBool("akka.stm.write-skew", true)
  val EXPLICIT_RETRIES = config.getBool("akka.stm.explicit-retries", false)
  val INTERRUPTIBLE    = config.getBool("akka.stm.interruptible", false)
  val SPECULATIVE      = config.getBool("akka.stm.speculative", true)
  val QUICK_RELEASE    = config.getBool("akka.stm.quick-release", true)
  val TRACE_LEVEL      = traceLevel(config.getString("akka.stm.trace-level", "none"))
  val HOOKS            = config.getBool("akka.stm.hooks", true)

  val DefaultTimeout = Duration(TIMEOUT, TIME_UNIT)

  def traceLevel(level: String) = level.toLowerCase match {
    case "coarse" | "course" => Transaction.TraceLevel.Coarse
    case "fine"              => Transaction.TraceLevel.Fine
    case _                   => Transaction.TraceLevel.None
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
   * @param explicitRetries  Whether explicit retries are allowed.
   * @param interruptible    Whether a blocking transaction can be interrupted.
   * @param speculative      Whether speculative configuration should be enabled.
   * @param quickRelease     Whether locks should be released as quickly as possible (before whole commit).
   * @param traceLevel       Transaction trace level.
   * @param hooks            Whether hooks for persistence modules and JTA should be added to the transaction.
   */
  def apply(familyName: String       = FAMILY_NAME,
            readonly: JBoolean       = READONLY,
            maxRetries: Int          = MAX_RETRIES,
            timeout: Duration        = DefaultTimeout,
            trackReads: JBoolean     = TRACK_READS,
            writeSkew: Boolean       = WRITE_SKEW,
            explicitRetries: Boolean = EXPLICIT_RETRIES,
            interruptible: Boolean   = INTERRUPTIBLE,
            speculative: Boolean     = SPECULATIVE,
            quickRelease: Boolean    = QUICK_RELEASE,
            traceLevel: TraceLevel   = TRACE_LEVEL,
            hooks: Boolean           = HOOKS) = {
    new TransactionConfig(familyName, readonly, maxRetries, timeout, trackReads, writeSkew,
                          explicitRetries, interruptible, speculative, quickRelease, traceLevel, hooks)
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
 * <p>explicitRetries - Whether explicit retries are allowed.
 * <p>interruptible   - Whether a blocking transaction can be interrupted.
 * <p>speculative     - Whether speculative configuration should be enabled.
 * <p>quickRelease    - Whether locks should be released as quickly as possible (before whole commit).
 * <p>traceLevel      - Transaction trace level.
 * <p>hooks           - Whether hooks for persistence modules and JTA should be added to the transaction.
 */
class TransactionConfig(val familyName: String       = TransactionConfig.FAMILY_NAME,
                        val readonly: JBoolean       = TransactionConfig.READONLY,
                        val maxRetries: Int          = TransactionConfig.MAX_RETRIES,
                        val timeout: Duration        = TransactionConfig.DefaultTimeout,
                        val trackReads: JBoolean     = TransactionConfig.TRACK_READS,
                        val writeSkew: Boolean       = TransactionConfig.WRITE_SKEW,
                        val explicitRetries: Boolean = TransactionConfig.EXPLICIT_RETRIES,
                        val interruptible: Boolean   = TransactionConfig.INTERRUPTIBLE,
                        val speculative: Boolean     = TransactionConfig.SPECULATIVE,
                        val quickRelease: Boolean    = TransactionConfig.QUICK_RELEASE,
                        val traceLevel: TraceLevel   = TransactionConfig.TRACE_LEVEL,
                        val hooks: Boolean           = TransactionConfig.HOOKS)

object DefaultTransactionConfig extends TransactionConfig

/**
 * Wrapper for transaction config, factory, and boilerplate. Used by atomic.
 */
object TransactionFactory {
  def apply(config: TransactionConfig) = new TransactionFactory(config)

  def apply(config: TransactionConfig, defaultName: String) = new TransactionFactory(config, defaultName)

  def apply(familyName: String       = TransactionConfig.FAMILY_NAME,
            readonly: JBoolean       = TransactionConfig.READONLY,
            maxRetries: Int          = TransactionConfig.MAX_RETRIES,
            timeout: Duration        = TransactionConfig.DefaultTimeout,
            trackReads: JBoolean     = TransactionConfig.TRACK_READS,
            writeSkew: Boolean       = TransactionConfig.WRITE_SKEW,
            explicitRetries: Boolean = TransactionConfig.EXPLICIT_RETRIES,
            interruptible: Boolean   = TransactionConfig.INTERRUPTIBLE,
            speculative: Boolean     = TransactionConfig.SPECULATIVE,
            quickRelease: Boolean    = TransactionConfig.QUICK_RELEASE,
            traceLevel: TraceLevel   = TransactionConfig.TRACE_LEVEL,
            hooks: Boolean           = TransactionConfig.HOOKS) = {
    val config = new TransactionConfig(
      familyName, readonly, maxRetries, timeout, trackReads, writeSkew,
      explicitRetries, interruptible, speculative, quickRelease, traceLevel, hooks)
    new TransactionFactory(config)
  }
}

/**
 * Wrapper for transaction config, factory, and boilerplate. Used by atomic.
 * Can be passed to atomic implicitly or explicitly.
 * <p/>
 * <pre>
 * implicit val txFactory = TransactionFactory(readonly = true)
 * ...
 * atomic {
 *   // do something within a readonly transaction
 * }
 * </pre>
 * <p/>
 * Can be created at different levels as needed. For example: as an implicit object
 * used throughout a package, as a static implicit val within a singleton object and
 * imported where needed, or as an implicit val within each instance of a class.
 * <p/>
 * If no explicit transaction factory is passed to atomic and there is no implicit
 * transaction factory in scope, then a default transaction factory is used.
 *
 * @see TransactionConfig for configuration options.
 */
class TransactionFactory(
  val config: TransactionConfig = DefaultTransactionConfig, 
  defaultName: String = TransactionConfig.FAMILY_NAME) { self =>

  // use the config family name if it's been set, otherwise defaultName - used by actors to set class name as default
  val familyName = if (config.familyName != TransactionConfig.FAMILY_NAME) config.familyName else defaultName

  val factory = {
    var builder = (getGlobalStmInstance().asInstanceOf[AlphaStm].getTransactionFactoryBuilder()
                   .setFamilyName(familyName)
                   .setMaxRetries(config.maxRetries)
                   .setTimeoutNs(config.timeout.toNanos)
                   .setWriteSkewAllowed(config.writeSkew)
                   .setExplicitRetryAllowed(config.explicitRetries)
                   .setInterruptible(config.interruptible)
                   .setSpeculativeConfigurationEnabled(config.speculative)
                   .setQuickReleaseEnabled(config.quickRelease)
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

  def addHooks = if (config.hooks) Transaction.attach
}
