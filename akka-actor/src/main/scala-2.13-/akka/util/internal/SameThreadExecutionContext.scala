/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util.internal

import akka.actor.ReflectiveDynamicAccess
import akka.annotation.InternalApi

import scala.concurrent.ExecutionContext

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object SameThreadExecutionContext {
  def apply(): ExecutionContext = {
    // we don't want to introduce a dependency on the actor system to use the same thread execution context
    val dynamicAccess = new ReflectiveDynamicAccess(getClass.getClassLoader)
    dynamicAccess.getObjectFor[ExecutionContext]("scala.concurrent.Future$InternalCallbackExecutor$").get
  }
}
