/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal

import akka.annotation.InternalApi
import org.slf4j.MDC

/**
 * INTERNAL API
 */
@InternalApi private[akka] object ActorMdc {
  val SourceKey = "akkaSource"

  def setMdc(source: String): Unit = {
    val mdcAdpater = MDC.getMDCAdapter
    mdcAdpater.put(SourceKey, source)
  }

  // MDC is cleared (if used) from aroundReceive in ActorAdapter after processing each message,
  // via ActorContextImpl.clearMdc()
  def clearMdc(): Unit =
    MDC.clear()

}
