/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.routing

import com.typesafe.config.ConfigFactory
import akka.actor.Address
import akka.actor.RootActorPath
import akka.testkit.AkkaSpec
import akka.routing.Routee
import akka.actor.ActorRef
import akka.actor.ActorSelection

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class WeightedRouteesSpec extends AkkaSpec(ConfigFactory.parseString("""
      akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
      akka.remote.netty.tcp.port = 0
      """)) {

  val a1 = Address("akka.tcp", "sys", "a1", 2551)
  val b1 = Address("akka.tcp", "sys", "b1", 2551)
  val c1 = Address("akka.tcp", "sys", "c1", 2551)
  val d1 = Address("akka.tcp", "sys", "d1", 2551)

  val selA = system.actorSelection(RootActorPath(a1) / "user" / "a")
  val selB = system.actorSelection(RootActorPath(b1) / "user" / "b")
  val selC = system.actorSelection(RootActorPath(c1) / "user" / "c")

  "WeightedRoutees" must {

    "allocate weighted refs" in {
      val weights = Map(a1 -> 1, b1 -> 3, c1 -> 10)
      val routees = Vector(selA, selB, selC) map Routee.apply
      val weighted = new WeightedRoutees(routees, a1, weights)

      weighted(1) must be(Routee(selA))
      2 to 4 foreach { weighted(_) must be(Routee(selB)) }
      5 to 14 foreach { weighted(_) must be(Routee(selC)) }
      weighted.total must be(14)
    }

    "check boundaries" in {
      val empty = new WeightedRoutees(Vector(), a1, Map.empty)
      empty.isEmpty must be(true)
      intercept[IllegalArgumentException] {
        empty.total
      }
      val routees = Vector(selA, selB, selC) map Routee.apply
      val weighted = new WeightedRoutees(routees, a1, Map.empty)
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
      val routees = Vector(selA, selB, selC) map Routee.apply
      val weighted = new WeightedRoutees(routees, a1, weights)

      weighted(1) must be(Routee(selA))
      2 to 8 foreach { weighted(_) must be(Routee(selB)) }
      // undefined, uses the mean of the weights, i.e. 4
      9 to 12 foreach { weighted(_) must be(Routee(selC)) }
      weighted.total must be(12)
    }

    "allocate weighted local refs" in {
      val weights = Map(a1 -> 2, b1 -> 1, c1 -> 10)
      val routees = Vector(testActor, selB, selC) map {
        case r: ActorRef       ⇒ Routee(r)
        case s: ActorSelection ⇒ Routee(s)
      }
      val weighted = new WeightedRoutees(routees, a1, weights)

      1 to 2 foreach { weighted(_) must be(Routee(testActor)) }
      3 to weighted.total foreach { weighted(_) must not be (Routee(testActor)) }
    }

    "not allocate ref with weight zero" in {
      val weights = Map(a1 -> 0, b1 -> 2, c1 -> 10)
      val routees = Vector(selA, selB, selC) map Routee.apply
      val weighted = new WeightedRoutees(routees, a1, weights)

      1 to weighted.total foreach { weighted(_) must not be (Routee(selA)) }
    }

  }
}
