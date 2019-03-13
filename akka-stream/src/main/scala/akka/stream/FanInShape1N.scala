/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable

@Deprecated
@deprecated(
  "FanInShape1N was removed because it was not used anywhere. Use a custom shape extending from FanInShape directly.",
  "2.5.5")
class FanInShape1N[-T0, -T1, +O](val n: Int, _init: FanInShape.Init[O]) extends FanInShape[O](_init) {

  //ports get added to `FanInShape.inlets` as a side-effect of calling `newInlet`
  val in0: Inlet[T0 @uncheckedVariance] = newInlet[T0]("in0")
  for (i <- 1 until n) newInlet[T1](s"in$i")

  def this(n: Int) = this(n, FanInShape.Name[O]("FanInShape1N"))
  def this(n: Int, name: String) = this(n, FanInShape.Name[O](name))
  def this(
      outlet: Outlet[O @uncheckedVariance],
      in0: Inlet[T0 @uncheckedVariance],
      inlets1: Array[Inlet[T1 @uncheckedVariance]]) =
    this(inlets1.length, FanInShape.Ports(outlet, in0 :: inlets1.toList))
  override protected def construct(init: FanInShape.Init[O @uncheckedVariance]): FanInShape[O] =
    new FanInShape1N(n, init)
  override def deepCopy(): FanInShape1N[T0, T1, O] = super.deepCopy().asInstanceOf[FanInShape1N[T0, T1, O]]

  @deprecated("Use 'inlets' or 'in(id)' instead.", "2.5.5")
  def in1Seq: immutable.IndexedSeq[Inlet[T1 @uncheckedVariance]] = _in1Seq

  // cannot deprecate a lazy val because of genjavadoc problem https://github.com/typesafehub/genjavadoc/issues/85
  private lazy val _in1Seq: immutable.IndexedSeq[Inlet[T1 @uncheckedVariance]] =
    inlets.tail //head is in0
    .toIndexedSeq.asInstanceOf[immutable.IndexedSeq[Inlet[T1]]]

  def in(n: Int): Inlet[T1 @uncheckedVariance] = {
    require(n > 0, "n must be > 0")
    inlets(n).asInstanceOf[Inlet[T1]]
  }
}
