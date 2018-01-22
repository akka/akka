/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed.scaladsl

import akka.actor.typed.Behavior
import akka.actor.typed.internal.LoggingBehaviorImpl
import akka.event.Logging.MDC

/**
 * Simple logging is always available throught [[ActorContext.log]], provided here are factories for more advanced
 * formed of logging.
 */
object LoggingBehaviors {

  /**
   * Provide a MDC ("Mapped Diagnostic Context") for logging from the actor.
   *
   * @param setupMdc Is invoked before each message to setup MDC applied to each logging statement done for that message
   *                 through the [[ActorContext.log]]. After the message has been processed the MDC is cleared.
   * @param behavior The behavior that this should be applied to.
   */
  def withMdc[T](
    setupMdc: T ⇒ MDC,
    behavior: Behavior[T]): Behavior[T] =
    LoggingBehaviorImpl.withMdc(setupMdc, behavior)

}
