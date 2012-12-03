/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.routing

import com.typesafe.config.ConfigFactory

import akka.actor.Address
import akka.actor.RootActorPath
import akka.testkit.AkkaSpec

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class WeightedRouteesSpec extends AkkaSpec(ConfigFactory.parseString("""
      akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
      akka.remote.netty.port = 0
      """)) {

  val a1 = Address("akka", "sys", "a1", 2551)
  val b1 = Address("akka", "sys", "b1", 2551)
  val c1 = Address("akka", "sys", "c1", 2551)
  val d1 = Address("akka", "sys", "d1", 2551)

  val refA = system.actorFor(RootActorPath(a1) / "user" / "a")
  val refB = system.actorFor(RootActorPath(b1) / "user" / "b")
  val refC = system.actorFor(RootActorPath(c1) / "user" / "c")

  "WeightedRoutees" must {

    "allocate weighted refs" in {
      val weights = Map(a1 -> 1, b1 -> 3, c1 -> 10)
      val refs = Vector(refA, refB, refC)
      val weighted = new WeightedRoutees(refs, a1, weights)

      weighted(1) must be(refA)
      2 to 4 foreach { weighted(_) must be(refB) }
      5 to 14 foreach { weighted(_) must be(refC) }
      weighted.total must be(14)
    }

    "check boundaries" in {
      val empty = new WeightedRoutees(Vector(), a1, Map.empty)
      empty.isEmpty must be(true)
      intercept[IllegalArgumentException] {
        empty.total
      }
      val weighted = new WeightedRoutees(Vector(refA, refB, refC), a1, Map.empty)
      weighted.total must be(3)
      intercept[IllegalArgumentException] {
        weighted(0)
      }
      intercept[IllegalArgumentException] {
        weighted(4)
      }
    }

    "allocate refs for undefined weight" in {
      val weights = Map(a1 -> 1, b1 -> 7)
      val refs = Vector(refA, refB, refC)
      val weighted = new WeightedRoutees(refs, a1, weights)

      weighted(1) must be(refA)
      2 to 8 foreach { weighted(_) must be(refB) }
      // undefined, uses the mean of the weights, i.e. 4
      9 to 12 foreach { weighted(_) must be(refC) }
      weighted.total must be(12)
    }

    "allocate weighted local refs" in {
      val weights = Map(a1 -> 2, b1 -> 1, c1 -> 10)
      val refs = Vector(testActor, refB, refC)
      val weighted = new WeightedRoutees(refs, a1, weights)

      1 to 2 foreach { weighted(_) must be(testActor) }
      3 to weighted.total foreach { weighted(_) must not be (testActor) }
    }

    "not allocate ref with weight zero" in {
      val weights = Map(a1 -> 0, b1 -> 2, c1 -> 10)
      val refs = Vector(refA, refB, refC)
      val weighted = new WeightedRoutees(refs, a1, weights)

      1 to weighted.total foreach { weighted(_) must not be (refA) }
    }

  }
}
