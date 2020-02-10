/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.dispatch.internal

import akka.annotation.InternalApi

import scala.concurrent.ExecutionContext

/**
 * Factory to create same thread ec. Not intended to be called from any other site than to create [[akka.dispatch.Dispatchers#sameThreadExecutionContext]]
 * Once Scala 2.12 is no longer supported this can be dropped in favour of directly using [[ExecutionContext.parasitic]]
 *
 * INTERNAL API
 */
@InternalApi
private[dispatch] object SameThreadExecutionContext {
  def apply(): ExecutionContext = ExecutionContext.parasitic
}
