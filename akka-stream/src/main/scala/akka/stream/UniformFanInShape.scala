/*
 * Copyright (C) 2014-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import scala.collection.immutable

object UniformFanInShape {
  def apply[I, O](outlet: Outlet[O], inlets: Inlet[I]*): UniformFanInShape[I, O] =
    new UniformFanInShape(inlets.size, FanInShape.Ports(outlet, inlets.toList))

  /** Java API */
  def create[I, O](outlet: Outlet[O], inlets: java.util.List[Inlet[I]]): UniformFanInShape[I, O] = {
    import akka.util.ccompat.JavaConverters._
    new UniformFanInShape(inlets.size, FanInShape.Ports(outlet, inlets.asScala.toList))
  }
}

class UniformFanInShape[-T, +O](val n: Int, _init: FanInShape.Init[O]) extends FanInShape[O](_init) {

  //ports get added to `FanInShape.inlets` as a side-effect of calling `newInlet`
  for (i <- 0 until n) newInlet[T](s"in$i")

  def this(n: Int) = this(n, FanInShape.Name[O]("UniformFanIn"))
  def this(n: Int, name: String) = this(n, FanInShape.Name[O](name))
  def this(outlet: Outlet[O], inlets: Array[Inlet[T]]) = this(inlets.length, FanInShape.Ports(outlet, inlets.toList))
  override protected def construct[N >: O](init: FanInShape.Init[N]): FanInShape[N] =
    new UniformFanInShape(n, init)
  override def deepCopy(): UniformFanInShape[T, O] = super.deepCopy().asInstanceOf[UniformFanInShape[T, O]]

  final override def inlets: immutable.Seq[Inlet[T]] =
    super.inlets.asInstanceOf[immutable.Seq[Inlet[T]]]

  @deprecated("Use 'inlets' or 'in(id)' instead.", "2.5.5")
  def inSeq: immutable.IndexedSeq[Inlet[T]] = _inSeq

  // cannot deprecate a lazy val because of genjavadoc problem https://github.com/typesafehub/genjavadoc/issues/85
  private lazy val _inSeq: immutable.IndexedSeq[Inlet[T]] = inlets.toIndexedSeq
  def in(n: Int): Inlet[T] = inlets(n)
}
