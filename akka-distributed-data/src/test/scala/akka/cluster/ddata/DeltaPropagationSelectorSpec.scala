/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata

import akka.actor.Address
import akka.cluster.ddata.Replicator.Internal.DataEnvelope
import akka.cluster.ddata.Replicator.Internal.DeltaPropagation
import org.scalactic.TypeCheckedTripleEquals
import org.scalatest.Matchers
import org.scalatest.WordSpec

object DeltaPropagationSelectorSpec {
  class TestSelector(override val allNodes: Vector[Address]) extends DeltaPropagationSelector {
    override val divisor = 5
    override def createDeltaPropagation(deltas: Map[String, ReplicatedData]): DeltaPropagation =
      DeltaPropagation(deltas.mapValues(d ⇒ DataEnvelope(d)))
  }

  val deltaA = GSet.empty[String] + "a"
  val deltaB = GSet.empty[String] + "b"
  val deltaC = GSet.empty[String] + "c"
}

class DeltaPropagationSelectorSpec extends WordSpec with Matchers with TypeCheckedTripleEquals {
  import DeltaPropagationSelectorSpec._
  val nodes = (2500 until 2600).map(n ⇒ Address("akka", "Sys", "localhost", n)).toVector

  "DeltaPropagationSelector" must {
    "collect none when no nodes" in {
      val selector = new TestSelector(Vector.empty)
      selector.update("A", deltaA)
      selector.collectPropagations() should ===(Map.empty[Address, DeltaPropagation])
      selector.cleanupDeltaEntries()
      selector.hasDeltaEntries("A") should ===(false)
    }

    "collect 1 when one node" in {
      val selector = new TestSelector(nodes.take(1))
      selector.update("A", deltaA)
      selector.update("B", deltaB)
      selector.cleanupDeltaEntries()
      selector.hasDeltaEntries("A") should ===(true)
      selector.hasDeltaEntries("B") should ===(true)
      val expected = DeltaPropagation(Map("A" → DataEnvelope(deltaA), "B" → DataEnvelope(deltaB)))
      selector.collectPropagations() should ===(Map(nodes(0) → expected))
      selector.collectPropagations() should ===(Map.empty[Address, DeltaPropagation])
      selector.cleanupDeltaEntries()
      selector.hasDeltaEntries("A") should ===(false)
      selector.hasDeltaEntries("B") should ===(false)
    }

    "collect 2+1 when three nodes" in {
      val selector = new TestSelector(nodes.take(3))
      selector.update("A", deltaA)
      selector.update("B", deltaB)
      val expected = DeltaPropagation(Map("A" → DataEnvelope(deltaA), "B" → DataEnvelope(deltaB)))
      selector.collectPropagations() should ===(Map(nodes(0) → expected, nodes(1) → expected))
      selector.cleanupDeltaEntries()
      selector.hasDeltaEntries("A") should ===(true)
      selector.hasDeltaEntries("B") should ===(true)
      selector.collectPropagations() should ===(Map(nodes(2) → expected))
      selector.collectPropagations() should ===(Map.empty[Address, DeltaPropagation])
      selector.cleanupDeltaEntries()
      selector.hasDeltaEntries("A") should ===(false)
      selector.hasDeltaEntries("B") should ===(false)
    }

    "keep track of deltas per node" in {
      val selector = new TestSelector(nodes.take(3))
      selector.update("A", deltaA)
      selector.update("B", deltaB)
      val expected1 = DeltaPropagation(Map("A" → DataEnvelope(deltaA), "B" → DataEnvelope(deltaB)))
      selector.collectPropagations() should ===(Map(nodes(0) → expected1, nodes(1) → expected1))
      // new update before previous was propagated to all nodes
      selector.update("C", deltaC)
      val expected2 = DeltaPropagation(Map("A" → DataEnvelope(deltaA), "B" → DataEnvelope(deltaB),
        "C" → DataEnvelope(deltaC)))
      val expected3 = DeltaPropagation(Map("C" → DataEnvelope(deltaC)))
      selector.collectPropagations() should ===(Map(nodes(2) → expected2, nodes(0) → expected3))
      selector.cleanupDeltaEntries()
      selector.hasDeltaEntries("A") should ===(false)
      selector.hasDeltaEntries("B") should ===(false)
      selector.hasDeltaEntries("C") should ===(true)
      selector.collectPropagations() should ===(Map(nodes(1) → expected3))
      selector.collectPropagations() should ===(Map.empty[Address, DeltaPropagation])
      selector.cleanupDeltaEntries()
      selector.hasDeltaEntries("C") should ===(false)
    }

    "merge updates that occur within same tick" in {
      val delta1 = GSet.empty[String] + "a1"
      val delta2 = GSet.empty[String] + "a2"
      val delta3 = GSet.empty[String] + "a3"
      val selector = new TestSelector(nodes.take(1))
      selector.update("A", delta1)
      selector.update("A", delta2)
      val expected1 = DeltaPropagation(Map("A" → DataEnvelope(delta1.merge(delta2))))
      selector.collectPropagations() should ===(Map(nodes(0) → expected1))
      selector.update("A", delta3)
      val expected2 = DeltaPropagation(Map("A" → DataEnvelope(delta3)))
      selector.collectPropagations() should ===(Map(nodes(0) → expected2))
      selector.collectPropagations() should ===(Map.empty[Address, DeltaPropagation])
    }

    "merge deltas" in {
      val delta1 = GSet.empty[String] + "a1"
      val delta2 = GSet.empty[String] + "a2"
      val delta3 = GSet.empty[String] + "a3"
      val selector = new TestSelector(nodes.take(3)) {
        override def nodesSliceSize(allNodesSize: Int): Int = 1
      }
      selector.update("A", delta1)
      val expected1 = DeltaPropagation(Map("A" → DataEnvelope(delta1)))
      selector.collectPropagations() should ===(Map(nodes(0) → expected1))

      selector.update("A", delta2)
      val expected2 = DeltaPropagation(Map("A" → DataEnvelope(delta1.merge(delta2))))
      selector.collectPropagations() should ===(Map(nodes(1) → expected2))

      selector.update("A", delta3)
      val expected3 = DeltaPropagation(Map("A" → DataEnvelope(delta1.merge(delta2).merge(delta3))))
      selector.collectPropagations() should ===(Map(nodes(2) → expected3))

      val expected4 = DeltaPropagation(Map("A" → DataEnvelope(delta2.merge(delta3))))
      selector.collectPropagations() should ===(Map(nodes(0) → expected4))

      val expected5 = DeltaPropagation(Map("A" → DataEnvelope(delta3)))
      selector.collectPropagations() should ===(Map(nodes(1) → expected5))

      selector.collectPropagations() should ===(Map.empty[Address, DeltaPropagation])
    }

    "calcualte right slice size" in {
      val selector = new TestSelector(nodes)
      selector.nodesSliceSize(0) should ===(0)
      selector.nodesSliceSize(1) should ===(1)
      (2 to 9).foreach { n ⇒
        withClue(s"n=$n") {
          selector.nodesSliceSize(n) should ===(2)
        }
      }
      (10 to 14).foreach { n ⇒
        withClue(s"n=$n") {
          selector.nodesSliceSize(n) should ===(3)
        }
      }
      (15 to 19).foreach { n ⇒
        withClue(s"n=$n") {
          selector.nodesSliceSize(n) should ===(4)
        }
      }
      (20 to 24).foreach { n ⇒
        withClue(s"n=$n") {
          selector.nodesSliceSize(n) should ===(5)
        }
      }
      (25 to 29).foreach { n ⇒
        withClue(s"n=$n") {
          selector.nodesSliceSize(n) should ===(6)
        }
      }
      (30 to 34).foreach { n ⇒
        withClue(s"n=$n") {
          selector.nodesSliceSize(n) should ===(7)
        }
      }
      (35 to 39).foreach { n ⇒
        withClue(s"n=$n") {
          selector.nodesSliceSize(n) should ===(8)
        }
      }
      (40 to 44).foreach { n ⇒
        withClue(s"n=$n") {
          selector.nodesSliceSize(n) should ===(9)
        }
      }
      (45 to 200).foreach { n ⇒
        withClue(s"n=$n") {
          selector.nodesSliceSize(n) should ===(10)
        }
      }
    }
  }
}
