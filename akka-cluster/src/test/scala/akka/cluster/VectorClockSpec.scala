/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.testkit.AkkaSpec

class VectorClockSpec extends AkkaSpec {
  import VectorClock._

  "A VectorClock" must {

    "have zero versions when created" in {
      val clock = VectorClock()
      clock.versions must be(Map())
    }

    "not happen before itself" in {
      val clock1 = VectorClock()
      val clock2 = VectorClock()

      clock1 <> clock2 must be(false)
    }

    "pass misc comparison test 1" in {
      val clock1_1 = VectorClock()
      val clock2_1 = clock1_1 :+ Node("1")
      val clock3_1 = clock2_1 :+ Node("2")
      val clock4_1 = clock3_1 :+ Node("1")

      val clock1_2 = VectorClock()
      val clock2_2 = clock1_2 :+ Node("1")
      val clock3_2 = clock2_2 :+ Node("2")
      val clock4_2 = clock3_2 :+ Node("1")

      clock4_1 <> clock4_2 must be(false)
    }

    "pass misc comparison test 2" in {
      val clock1_1 = VectorClock()
      val clock2_1 = clock1_1 :+ Node("1")
      val clock3_1 = clock2_1 :+ Node("2")
      val clock4_1 = clock3_1 :+ Node("1")

      val clock1_2 = VectorClock()
      val clock2_2 = clock1_2 :+ Node("1")
      val clock3_2 = clock2_2 :+ Node("2")
      val clock4_2 = clock3_2 :+ Node("1")
      val clock5_2 = clock4_2 :+ Node("3")

      clock4_1 < clock5_2 must be(true)
    }

    "pass misc comparison test 3" in {
      var clock1_1 = VectorClock()
      val clock2_1 = clock1_1 :+ Node("1")

      val clock1_2 = VectorClock()
      val clock2_2 = clock1_2 :+ Node("2")

      clock2_1 <> clock2_2 must be(true)
    }

    "pass misc comparison test 4" in {
      val clock1_3 = VectorClock()
      val clock2_3 = clock1_3 :+ Node("1")
      val clock3_3 = clock2_3 :+ Node("2")
      val clock4_3 = clock3_3 :+ Node("1")

      val clock1_4 = VectorClock()
      val clock2_4 = clock1_4 :+ Node("1")
      val clock3_4 = clock2_4 :+ Node("1")
      val clock4_4 = clock3_4 :+ Node("3")

      clock4_3 <> clock4_4 must be(true)
    }

    "pass misc comparison test 5" in {
      val clock1_1 = VectorClock()
      val clock2_1 = clock1_1 :+ Node("2")
      val clock3_1 = clock2_1 :+ Node("2")

      val clock1_2 = VectorClock()
      val clock2_2 = clock1_2 :+ Node("1")
      val clock3_2 = clock2_2 :+ Node("2")
      val clock4_2 = clock3_2 :+ Node("2")
      val clock5_2 = clock4_2 :+ Node("3")

      clock3_1 < clock5_2 must be(true)
      clock5_2 > clock3_1 must be(true)
    }

    "pass misc comparison test 6" in {
      val clock1_1 = VectorClock()
      val clock2_1 = clock1_1 :+ Node("1")
      val clock3_1 = clock2_1 :+ Node("2")

      val clock1_2 = VectorClock()
      val clock2_2 = clock1_2 :+ Node("1")
      val clock3_2 = clock2_2 :+ Node("1")

      clock3_1 <> clock3_2 must be(true)
      clock3_2 <> clock3_1 must be(true)
    }

    "pass misc comparison test 7" in {
      val clock1_1 = VectorClock()
      val clock2_1 = clock1_1 :+ Node("1")
      val clock3_1 = clock2_1 :+ Node("2")
      val clock4_1 = clock3_1 :+ Node("2")
      val clock5_1 = clock4_1 :+ Node("3")

      val clock1_2 = clock4_1
      val clock2_2 = clock1_2 :+ Node("2")
      val clock3_2 = clock2_2 :+ Node("2")

      clock5_1 <> clock3_2 must be(true)
      clock3_2 <> clock5_1 must be(true)
    }

    "pass misc comparison test 8" in {
      val clock1_1 = VectorClock()
      val clock2_1 = clock1_1 :+ Node.fromHash("1")
      val clock3_1 = clock2_1 :+ Node.fromHash("3")

      val clock1_2 = clock3_1 :+ Node.fromHash("2")

      val clock4_1 = clock3_1 :+ Node.fromHash("3")

      clock4_1 <> clock1_2 must be(true)
      clock1_2 <> clock4_1 must be(true)
    }

    "correctly merge two clocks" in {
      val node1 = Node("1")
      val node2 = Node("2")
      val node3 = Node("3")

      val clock1_1 = VectorClock()
      val clock2_1 = clock1_1 :+ node1
      val clock3_1 = clock2_1 :+ node2
      val clock4_1 = clock3_1 :+ node2
      val clock5_1 = clock4_1 :+ node3

      val clock1_2 = clock4_1
      val clock2_2 = clock1_2 :+ node2
      val clock3_2 = clock2_2 :+ node2

      val merged1 = clock3_2 merge clock5_1
      merged1.versions.size must be(3)
      merged1.versions.contains(node1) must be(true)
      merged1.versions.contains(node2) must be(true)
      merged1.versions.contains(node3) must be(true)

      val merged2 = clock5_1 merge clock3_2
      merged2.versions.size must be(3)
      merged2.versions.contains(node1) must be(true)
      merged2.versions.contains(node2) must be(true)
      merged2.versions.contains(node3) must be(true)

      clock3_2 < merged1 must be(true)
      clock5_1 < merged1 must be(true)

      clock3_2 < merged2 must be(true)
      clock5_1 < merged2 must be(true)

      merged1 == merged2 must be(true)
    }

    "correctly merge two disjoint vector clocks" in {
      val node1 = Node("1")
      val node2 = Node("2")
      val node3 = Node("3")
      val node4 = Node("4")

      val clock1_1 = VectorClock()
      val clock2_1 = clock1_1 :+ node1
      val clock3_1 = clock2_1 :+ node2
      val clock4_1 = clock3_1 :+ node2
      val clock5_1 = clock4_1 :+ node3

      val clock1_2 = VectorClock()
      val clock2_2 = clock1_2 :+ node4
      val clock3_2 = clock2_2 :+ node4

      val merged1 = clock3_2 merge clock5_1
      merged1.versions.size must be(4)
      merged1.versions.contains(node1) must be(true)
      merged1.versions.contains(node2) must be(true)
      merged1.versions.contains(node3) must be(true)
      merged1.versions.contains(node4) must be(true)

      val merged2 = clock5_1 merge clock3_2
      merged2.versions.size must be(4)
      merged2.versions.contains(node1) must be(true)
      merged2.versions.contains(node2) must be(true)
      merged2.versions.contains(node3) must be(true)
      merged2.versions.contains(node4) must be(true)

      clock3_2 < merged1 must be(true)
      clock5_1 < merged1 must be(true)

      clock3_2 < merged2 must be(true)
      clock5_1 < merged2 must be(true)

      merged1 == merged2 must be(true)
    }

    "pass blank clock incrementing" in {
      val node1 = Node("1")
      val node2 = Node("2")

      val v1 = VectorClock()
      val v2 = VectorClock()

      val vv1 = v1 :+ node1
      val vv2 = v2 :+ node2

      (vv1 > v1) must equal(true)
      (vv2 > v2) must equal(true)

      (vv1 > v2) must equal(true)
      (vv2 > v1) must equal(true)

      (vv2 > vv1) must equal(false)
      (vv1 > vv2) must equal(false)
    }

    "pass merging behavior" in {
      val node1 = Node("1")
      val node2 = Node("2")
      val node3 = Node("3")

      val a = VectorClock()
      val b = VectorClock()

      val a1 = a :+ node1
      val b1 = b :+ node2

      var a2 = a1 :+ node1
      var c = a2.merge(b1)
      var c1 = c :+ node3

      (c1 > a2) must equal(true)
      (c1 > b1) must equal(true)
    }
  }
}
