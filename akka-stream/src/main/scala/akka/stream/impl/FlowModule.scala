/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import akka.stream._
import akka.stream.impl.StreamLayout.Module
import akka.event.Logging

/**
 * INTERNAL API
 */
private[stream] trait FlowModule[In, Out, Mat] extends StreamLayout.AtomicModule {
  override def replaceShape(s: Shape) =
    if (s != shape) throw new UnsupportedOperationException("cannot replace the shape of a FlowModule")
    else this

  val inPort = Inlet[In]("Flow.in")
  val outPort = Outlet[Out]("Flow.out")
  override val shape = new FlowShape(inPort, outPort)

  protected def label: String = Logging.simpleName(this)
  final override def toString: String = f"$label [${System.identityHashCode(this)}%08x]"
}
