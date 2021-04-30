/*
 * Copyright (C) 2014-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import scala.collection.immutable

object UniformFanOutShape {
  def apply[I, O](inlet: Inlet[I], outlets: Outlet[O]*): UniformFanOutShape[I, O] =
    new UniformFanOutShape(outlets.size, FanOutShape.Ports(inlet, outlets.toList))
}

class UniformFanOutShape[-I, +O](n: Int, _init: FanOutShape.Init[I]) extends FanOutShape[I](_init) {

  //initialize by side-effect
  for (i <- 0 until n) newOutlet[O](s"out$i")

  def this(n: Int) = this(n, FanOutShape.Name[I]("UniformFanOut"))
  def this(n: Int, name: String) = this(n, FanOutShape.Name[I](name))
  def this(inlet: Inlet[I], outlets: Array[Outlet[O]]) = this(outlets.length, FanOutShape.Ports(inlet, outlets.toList))
  override protected def construct[J <: I](init: FanOutShape.Init[J]): FanOutShape[J] =
    new UniformFanOutShape(n, init)
  override def deepCopy(): UniformFanOutShape[I, O] = super.deepCopy().asInstanceOf[UniformFanOutShape[I, O]]

  final override def outlets: immutable.Seq[Outlet[O]] =
    super.outlets.asInstanceOf[immutable.Seq[Outlet[O]]]

  @Deprecated
  @deprecated("use 'outlets' or 'out(id)' instead", "2.5.5")
  def outArray: Array[_ <: Outlet[O]] = _outArray

  // cannot deprecate a lazy val because of genjavadoc problem https://github.com/typesafehub/genjavadoc/issues/85
  private lazy val _outArray: Array[_ <: Outlet[O]] = outlets.toArray
  def out(n: Int): Outlet[O] = outlets(n)
}
