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
  val TagsKey = "akkaTags"

  /**
   * @param tags empty string for no tags, a single tag or a comma separated list of tags
   */
  def setMdc(source: String, tags: String): Unit = {
    MDC.put(SourceKey, source)
    if (tags.nonEmpty)
      MDC.put(TagsKey, tags)
  }

  // MDC is cleared (if used) from aroundReceive in ActorAdapter after processing each message,
  // via ActorContextImpl.clearMdc()
  def clearMdc(): Unit =
    MDC.clear()

}
