/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.dispatch.internal

import akka.annotation.InternalApi

import scala.concurrent.ExecutionContext

/**
 * Factory to create same thread ec. Not intended to be called from any other site than to create [[akka.dispatch.ExecutionContexts#parasitic]]
 *
 * INTERNAL API
 */
@InternalApi
private[dispatch] object SameThreadExecutionContext {
  def apply(): ExecutionContext = ExecutionContext.parasitic
}
