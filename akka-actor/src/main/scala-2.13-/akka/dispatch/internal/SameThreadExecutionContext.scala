/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.dispatch.internal

import akka.actor.ReflectiveDynamicAccess
import akka.annotation.InternalApi
import akka.dispatch.BatchingExecutor

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

/**
 * Factory to create same thread ec. Not intended to be called from any other site than to create [[akka.dispatch.Dispatchers#sameThreadExecutionContext]]
 *
 * INTERNAL API
 */
@InternalApi
private[dispatch] object SameThreadExecutionContext {
  def apply(): ExecutionContext = {
    try {
      // we don't want to introduce a dependency on the actor system to use the same thread execution context
      val dynamicAccess = new ReflectiveDynamicAccess(getClass.getClassLoader)
      dynamicAccess.getObjectFor[ExecutionContext]("scala.concurrent.Future$InternalCallbackExecutor$").get
    } catch {
      case NonFatal(_) =>
        // fallback to custom impl in case reflection is not available/possible
        new ExecutionContext with BatchingExecutor {
          override protected def unbatchedExecute(runnable: Runnable): Unit = runnable.run()
          override protected def resubmitOnBlock: Boolean = false // No point since we execute on same thread
          override def reportFailure(t: Throwable): Unit =
            throw new IllegalStateException("exception in sameThreadExecutionContext", t)
        }
    }
  }
}
