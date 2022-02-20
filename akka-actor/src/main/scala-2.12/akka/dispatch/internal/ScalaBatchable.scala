/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.dispatch.internal

import akka.annotation.InternalApi

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object ScalaBatchable {

  // see Scala 2.13 source tree for explanation
  def isBatchable(runnable: Runnable): Boolean = runnable match {
    case b: akka.dispatch.Batchable             => b.isBatchable
    case _: scala.concurrent.OnCompleteRunnable => true
    case _                                      => false
  }

}
