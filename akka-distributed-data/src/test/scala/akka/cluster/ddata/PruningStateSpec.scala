/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster.ddata

import akka.actor.Address
import akka.cluster.UniqueAddress
import org.scalatest.Matchers
import org.scalatest.WordSpec

class PruningStateSpec extends WordSpec with Matchers {
  import PruningState._

  val node1 = UniqueAddress(Address("akka.tcp", "Sys", "localhost", 2551), 1)
  val node2 = UniqueAddress(node1.address.copy(port = Some(2552)), 2)
  val node3 = UniqueAddress(node1.address.copy(port = Some(2553)), 3)
  val node4 = UniqueAddress(node1.address.copy(port = Some(2554)), 4)

  "Pruning state" must {

    "merge phase correctly" in {
      val p1 = PruningState(node1, PruningInitialized(Set.empty))
      val p2 = PruningState(node1, PruningPerformed)
      p1.merge(p2).phase should be(PruningPerformed)
      p2.merge(p1).phase should be(PruningPerformed)
    }

    "merge owner correctly" in {
      val p1 = PruningState(node1, PruningInitialized(Set.empty))
      val p2 = PruningState(node2, PruningInitialized(Set.empty))
      val expected = PruningState(node1, PruningInitialized(Set.empty))
      p1.merge(p2) should be(expected)
      p2.merge(p1) should be(expected)
    }

    "merge seen correctly" in {
      val p1 = PruningState(node1, PruningInitialized(Set(node2.address)))
      val p2 = PruningState(node1, PruningInitialized(Set(node4.address)))
      val expected = PruningState(node1, PruningInitialized(Set(node2.address, node4.address)))
      p1.merge(p2) should be(expected)
      p2.merge(p1) should be(expected)
    }

  }
}
