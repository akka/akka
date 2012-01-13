/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.event

import akka.actor.GlobalActorSystem

/**
 * Migration replacement for `akka.event.EventHandler`
 */
@deprecated("use Logging instead", "2.0")
object OldEventHandler {

  @deprecated("use Logging instead", "2.0")
  def error(cause: Throwable, instance: AnyRef, message: ⇒ String) {
    val log = Logging.getLogger(GlobalActorSystem, instance)
    if (log.isErrorEnabled) log.error(cause, message)
  }

  @deprecated("use Logging instead", "2.0")
  def error(cause: Throwable, instance: AnyRef, message: Any) {
    val log = Logging.getLogger(GlobalActorSystem, instance)
    if (log.isErrorEnabled) log.error(cause, message.toString)
  }

  @deprecated("use Logging instead", "2.0")
  def error(instance: AnyRef, message: ⇒ String) {
    val log = Logging.getLogger(GlobalActorSystem, instance)
    if (log.isErrorEnabled) log.error(message.toString)
  }

  @deprecated("use Logging instead", "2.0")
  def error(instance: AnyRef, message: Any) {
    val log = Logging.getLogger(GlobalActorSystem, instance)
    if (log.isErrorEnabled) log.error(message.toString)
  }

  @deprecated("use Logging instead", "2.0")
  def warning(instance: AnyRef, message: ⇒ String) {
    val log = Logging.getLogger(GlobalActorSystem, instance)
    if (log.isWarningEnabled) log.warning(message)
  }

  @deprecated("use Logging instead", "2.0")
  def warning(instance: AnyRef, message: Any) {
    val log = Logging.getLogger(GlobalActorSystem, instance)
    if (log.isWarningEnabled) log.warning(message.toString)
  }

  @deprecated("use Logging instead", "2.0")
  def info(instance: AnyRef, message: ⇒ String) {
    val log = Logging.getLogger(GlobalActorSystem, instance)
    if (log.isInfoEnabled) log.info(message)
  }

  @deprecated("use Logging instead", "2.0")
  def info(instance: AnyRef, message: Any) {
    val log = Logging.getLogger(GlobalActorSystem, instance)
    if (log.isInfoEnabled) log.info(message.toString)
  }

  @deprecated("use Logging instead", "2.0")
  def debug(instance: AnyRef, message: ⇒ String) {
    val log = Logging.getLogger(GlobalActorSystem, instance)
    if (log.isDebugEnabled) log.debug(message)
  }

  @deprecated("use Logging instead", "2.0")
  def debug(instance: AnyRef, message: Any) {
    val log = Logging.getLogger(GlobalActorSystem, instance)
    if (log.isDebugEnabled) log.debug(message.toString)
  }

  @deprecated("use Logging instead", "2.0")
  def isInfoEnabled = Logging.getLogger(GlobalActorSystem, this).isInfoEnabled

  @deprecated("use Logging instead", "2.0")
  def isDebugEnabled = Logging.getLogger(GlobalActorSystem, this).isDebugEnabled

}
