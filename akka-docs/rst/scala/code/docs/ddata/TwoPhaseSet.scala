/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.ddata

import akka.cluster.ddata.ReplicatedData
import akka.cluster.ddata.GSet

//#twophaseset
case class TwoPhaseSet(
  adds: GSet[String] = GSet.empty,
  removals: GSet[String] = GSet.empty)
  extends ReplicatedData {
  type T = TwoPhaseSet

  def add(element: String): TwoPhaseSet =
    copy(adds = adds.add(element))

  def remove(element: String): TwoPhaseSet =
    copy(removals = removals.add(element))

  def elements: Set[String] = adds.elements -- removals.elements

  override def merge(that: TwoPhaseSet): TwoPhaseSet =
    copy(
      adds = GSet(this.adds.elements ++ that.adds.elements),
      removals = GSet(this.removals.elements ++ that.removals.elements))
}
//#twophaseset
