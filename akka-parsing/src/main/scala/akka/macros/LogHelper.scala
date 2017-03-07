/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.macros

import akka.annotation.InternalApi
import akka.event.LoggingAdapter

import scala.reflect.macros.blackbox

/**
 * INTERNAL API
 *
 * Provides access to a LoggingAdapter which each call guarded by `if (log.isXXXEnabled)` to prevent evaluating
 * the message expression eagerly.
 */
@InternalApi
private[akka] trait LogHelper {
  def log: LoggingAdapter

  /** Override to prefix every log message with a user-defined context string */
  protected def prefixString: String = ""

  def debug(msg: String): Unit = macro LogHelper.debugMacro
  def info(msg: String): Unit = macro LogHelper.infoMacro
  def warning(msg: String): Unit = macro LogHelper.warningMacro
}

/** INTERNAL API */
@InternalApi
private[akka] object LogHelper {
  type LoggerContext = blackbox.Context { type PrefixType = LogHelper }

  def debugMacro(ctx: LoggerContext)(msg: ctx.Expr[String]): ctx.Expr[Unit] =
    ctx.universe.reify {
      {
        val logHelper = ctx.prefix.splice
        val log = logHelper.log
        if (log.isDebugEnabled)
          log.debug(logHelper.prefixString + msg.splice)
      }
    }
  def infoMacro(ctx: LoggerContext)(msg: ctx.Expr[String]): ctx.Expr[Unit] =
    ctx.universe.reify {
      {
        val logHelper = ctx.prefix.splice
        val log = logHelper.log
        if (log.isInfoEnabled)
          log.info(logHelper.prefixString + msg.splice)
      }
    }
  def warningMacro(ctx: LoggerContext)(msg: ctx.Expr[String]): ctx.Expr[Unit] =
    ctx.universe.reify {
      {
        val logHelper = ctx.prefix.splice
        val log = logHelper.log
        if (log.isWarningEnabled)
          log.warning(logHelper.prefixString + msg.splice)
      }
    }
}
