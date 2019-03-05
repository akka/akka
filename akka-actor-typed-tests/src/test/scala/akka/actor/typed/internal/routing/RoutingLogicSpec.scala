/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.routing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import org.scalatest.Matchers
import org.scalatest.WordSpecLike

class RoutingLogicSpec extends ScalaTestWithActorTestKit with WordSpecLike with Matchers {

  "The round robin routing logic" must {

    "round robin" in {
      val refA = TestProbe("a").ref
      val refB = TestProbe("b").ref
      val refC = TestProbe("c").ref
      val allRoutees = Array(refA, refB, refC)

      val logic = new RoutingLogics.RoundRobinLogic[Any]

      logic.selectRoutee(allRoutees) should ===(refA)
      logic.selectRoutee(allRoutees) should ===(refB)
      logic.selectRoutee(allRoutees) should ===(refC)
      logic.selectRoutee(allRoutees) should ===(refA)
    }

    "not skip one on removal" in {
      val refA = TestProbe("a").ref
      val refB = TestProbe("b").ref
      val refC = TestProbe("c").ref
      val allRoutees = Array(refA, refB, refC)

      val logic = new RoutingLogics.RoundRobinLogic[Any]
      logic.selectRoutee(allRoutees) should ===(refA)
      logic.selectRoutee(allRoutees) should ===(refB)

      val bRemoved = Array(refA, refC)
      logic.routeesUpdated(allRoutees, bRemoved)
      logic.selectRoutee(bRemoved) should ===(refC)
    }

    "handle last one removed" in {
      val refA = TestProbe("a").ref
      val refB = TestProbe("b").ref
      val allRoutees = Array(refA, refB)

      val logic = new RoutingLogics.RoundRobinLogic[Any]
      logic.selectRoutee(allRoutees) should ===(refA)

      val bRemoved = Array(refA)
      logic.routeesUpdated(allRoutees, bRemoved)
      logic.selectRoutee(bRemoved) should ===(refA)
    }

    "move on to next when several removed" in {
      // this can happen with a group router update
      val refA = TestProbe("a").ref
      val refB = TestProbe("b").ref
      val refC = TestProbe("c").ref
      val refD = TestProbe("d").ref
      val allRoutees = Array(refA, refB, refC, refD)

      val logic = new RoutingLogics.RoundRobinLogic[Any]
      logic.selectRoutee(allRoutees) should ===(refA)
      logic.selectRoutee(allRoutees) should ===(refB)

      val severalRemoved = Array(refA, refC)
      logic.routeesUpdated(allRoutees, severalRemoved)
      logic.selectRoutee(severalRemoved) should ===(refC)
    }

    "wrap around when several removed" in {
      // this can happen with a group router update
      val refA = TestProbe("a").ref
      val refB = TestProbe("b").ref
      val refC = TestProbe("c").ref
      val refD = TestProbe("d").ref
      val allRoutees = Array(refA, refB, refC, refD)

      val logic = new RoutingLogics.RoundRobinLogic[Any]
      logic.selectRoutee(allRoutees) should ===(refA)
      logic.selectRoutee(allRoutees) should ===(refB)
      logic.selectRoutee(allRoutees) should ===(refC)

      val severalRemoved = Array(refA, refC)
      logic.routeesUpdated(allRoutees, severalRemoved)
      logic.selectRoutee(severalRemoved) should ===(refA)
    }

    "pick first in with a completely new set of routees" in {
      // this can happen with a group router update
      val refA = TestProbe("a").ref
      val refB = TestProbe("b").ref
      val refC = TestProbe("c").ref
      val refD = TestProbe("d").ref
      val initialRoutees = Array(refA, refB)

      val logic = new RoutingLogics.RoundRobinLogic[Any]
      logic.selectRoutee(initialRoutees) should ===(refA)
      logic.selectRoutee(initialRoutees) should ===(refB)
      logic.selectRoutee(initialRoutees) should ===(refA)

      val severalRemoved = Array(refC, refD)
      logic.routeesUpdated(initialRoutees, severalRemoved)
      logic.selectRoutee(severalRemoved) should ===(refC)
    }

  }

  "The random logic" must {
    "select randomly" in {
      val refA = TestProbe("a").ref
      val refB = TestProbe("b").ref
      val refC = TestProbe("c").ref
      val refD = TestProbe("d").ref
      val routees = Array(refA, refB, refC, refD)

      val logic = RoutingLogics.randomLogic[Any]

      (0 to 10).foreach { _ ⇒
        // not much to verify here, but let's exercise it at least
        val routee = logic.selectRoutee(routees)
        routees should contain(routee)
      }

    }

  }
}
