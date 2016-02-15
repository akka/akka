/**
 * Copyright (C) 2015-2016 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream._
import akka.stream.impl.StreamLayout.Module

/**
 * INTERNAL API
 */
private[stream] trait FlowModule[In, Out, Mat] extends StreamLayout.Module {
  override def replaceShape(s: Shape) =
    if (s != shape) throw new UnsupportedOperationException("cannot replace the shape of a FlowModule")
    else this

  val inPort = Inlet[In]("Flow.in")
  val outPort = Outlet[Out]("Flow.out")
  override val shape = new FlowShape(inPort, outPort)

  override def subModules: Set[Module] = Set.empty
}

