/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import java.net.InetSocketAddress
import akka.testkit.AkkaSpec
import akka.actor.ActorSystem

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

      val clock1_2 = VectorClock()
      val clock2_2 = clock1_2 :+ Node("2")
      val clock3_2 = clock2_2 :+ Node("2")

      clock5_1 <> clock3_2 must be(true)
      clock3_2 <> clock5_1 must be(true)
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

      val clock1_2 = VectorClock()
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
      val node3 = Node("3")

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

  "An instance of Versioned" must {
    class TestVersioned(val version: VectorClock = VectorClock()) extends Versioned[TestVersioned] {
      def :+(node: Node): TestVersioned = new TestVersioned(version :+ node)
    }

    import Versioned.latestVersionOf

    "have zero versions when created" in {
      val versioned = new TestVersioned()
      versioned.version.versions must be(Map())
    }

    "happen before an identical versioned with a single additional event" in {
      val versioned1_1 = new TestVersioned()
      val versioned2_1 = versioned1_1 :+ Node("1")
      val versioned3_1 = versioned2_1 :+ Node("2")
      val versioned4_1 = versioned3_1 :+ Node("1")

      val versioned1_2 = new TestVersioned()
      val versioned2_2 = versioned1_2 :+ Node("1")
      val versioned3_2 = versioned2_2 :+ Node("2")
      val versioned4_2 = versioned3_2 :+ Node("1")
      val versioned5_2 = versioned4_2 :+ Node("3")

      latestVersionOf[TestVersioned](versioned4_1, versioned5_2) must be(versioned5_2)
    }

    "pass misc comparison test 1" in {
      var versioned1_1 = new TestVersioned()
      val versioned2_1 = versioned1_1 :+ Node("1")

      val versioned1_2 = new TestVersioned()
      val versioned2_2 = versioned1_2 :+ Node("2")

      latestVersionOf[TestVersioned](versioned2_1, versioned2_2) must be(versioned2_2)
    }

    "pass misc comparison test 2" in {
      val versioned1_3 = new TestVersioned()
      val versioned2_3 = versioned1_3 :+ Node("1")
      val versioned3_3 = versioned2_3 :+ Node("2")
      val versioned4_3 = versioned3_3 :+ Node("1")

      val versioned1_4 = new TestVersioned()
      val versioned2_4 = versioned1_4 :+ Node("1")
      val versioned3_4 = versioned2_4 :+ Node("1")
      val versioned4_4 = versioned3_4 :+ Node("3")

      latestVersionOf[TestVersioned](versioned4_3, versioned4_4) must be(versioned4_4)
    }

    "pass misc comparison test 3" in {
      val versioned1_1 = new TestVersioned()
      val versioned2_1 = versioned1_1 :+ Node("2")
      val versioned3_1 = versioned2_1 :+ Node("2")

      val versioned1_2 = new TestVersioned()
      val versioned2_2 = versioned1_2 :+ Node("1")
      val versioned3_2 = versioned2_2 :+ Node("2")
      val versioned4_2 = versioned3_2 :+ Node("2")
      val versioned5_2 = versioned4_2 :+ Node("3")

      latestVersionOf[TestVersioned](versioned3_1, versioned5_2) must be(versioned5_2)
    }

    "pass misc comparison test 4" in {
      val versioned1_1 = new TestVersioned()
      val versioned2_1 = versioned1_1 :+ Node("1")
      val versioned3_1 = versioned2_1 :+ Node("2")
      val versioned4_1 = versioned3_1 :+ Node("2")
      val versioned5_1 = versioned4_1 :+ Node("3")

      val versioned1_2 = new TestVersioned()
      val versioned2_2 = versioned1_2 :+ Node("2")
      val versioned3_2 = versioned2_2 :+ Node("2")

      latestVersionOf[TestVersioned](versioned5_1, versioned3_2) must be(versioned3_2)
    }
  }
}
