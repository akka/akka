/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util.internal

import akka.annotation.InternalApi

import scala.concurrent.ExecutionContext

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object SameThreadExecutionContext {
  def apply(): ExecutionContext = ExecutionContext.parasitic
}
