/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import scala.collection.immutable
import scala.annotation.unchecked.uncheckedVariance

object UniformFanInShape {
  def apply[I, O](outlet: Outlet[O], inlets: Inlet[I]*): UniformFanInShape[I, O] =
    new UniformFanInShape(inlets.size, FanInShape.Ports(outlet, inlets.toList))
}

class UniformFanInShape[-T, +O](val n: Int, _init: FanInShape.Init[O]) extends FanInShape[O](_init) {

  //ports get added to `FanInShape.inlets` as a side-effect of calling `newInlet`
  for (i <- 0 until n) newInlet[T](s"in$i")

  def this(n: Int) = this(n, FanInShape.Name[O]("UniformFanIn"))
  def this(n: Int, name: String) = this(n, FanInShape.Name[O](name))
  def this(outlet: Outlet[O], inlets: Array[Inlet[T]]) = this(inlets.length, FanInShape.Ports(outlet, inlets.toList))
  override protected def construct(init: FanInShape.Init[O @uncheckedVariance]): FanInShape[O] =
    new UniformFanInShape(n, init)
  override def deepCopy(): UniformFanInShape[T, O] = super.deepCopy().asInstanceOf[UniformFanInShape[T, O]]

  final override def inlets: immutable.Seq[Inlet[T @uncheckedVariance]] =
    super.inlets.asInstanceOf[immutable.Seq[Inlet[T]]]

  @deprecated("Use 'inlets' or 'in(id)' instead.", "2.5.5")
  def inSeq: immutable.IndexedSeq[Inlet[T @uncheckedVariance]] = _inSeq

  // cannot deprecate a lazy val because of genjavadoc problem https://github.com/typesafehub/genjavadoc/issues/85
  private lazy val _inSeq: immutable.IndexedSeq[Inlet[T @uncheckedVariance]] = inlets.toIndexedSeq
  def in(n: Int): Inlet[T @uncheckedVariance] = inlets(n)
}
