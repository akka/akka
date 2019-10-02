/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal

import akka.annotation.InternalApi
import akka.util.OptionVal
import org.slf4j.MDC

/**
 * INTERNAL API
 */
@InternalApi private[akka] object ActorMdc {
  val SourceKey = "akkaSource"
  val TagsKey = "akkaTags"

  def setMdc(source: String, tags: OptionVal[String]): Unit = {
    val mdcAdapter = MDC.getMDCAdapter
    mdcAdapter.put(SourceKey, source)
    tags match {
      case OptionVal.Some(tags) if tags.nonEmpty =>
        mdcAdapter.put(TagsKey, tags)
      case _ =>
    }
  }

  // MDC is cleared (if used) from aroundReceive in ActorAdapter after processing each message,
  // via ActorContextImpl.clearMdc()
  def clearMdc(): Unit =
    MDC.clear()

}
