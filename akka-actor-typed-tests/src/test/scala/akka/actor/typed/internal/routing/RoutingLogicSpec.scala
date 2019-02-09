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
      val allRoutees = Set(refA, refB, refC)

      val logic = new RoutingLogics.RoundRobinLogic[Any]

      logic.routeesUpdated(allRoutees)
      logic.selectRoutee() should ===(refA)
      logic.selectRoutee() should ===(refB)
      logic.selectRoutee() should ===(refC)
      logic.selectRoutee() should ===(refA)
    }

    "not skip one on removal" in {
      val refA = TestProbe("a").ref
      val refB = TestProbe("b").ref
      val refC = TestProbe("c").ref
      val allRoutees = Set(refA, refB, refC)

      val logic = new RoutingLogics.RoundRobinLogic[Any]
      logic.routeesUpdated(allRoutees)
      logic.selectRoutee() should ===(refA)
      logic.selectRoutee() should ===(refB)

      val bRemoved = Set(refA, refC)
      logic.routeesUpdated(bRemoved)
      logic.selectRoutee() should ===(refC)
    }

    "handle last one removed" in {
      val refA = TestProbe("a").ref
      val refB = TestProbe("b").ref
      val allRoutees = Set(refA, refB)

      val logic = new RoutingLogics.RoundRobinLogic[Any]
      logic.routeesUpdated(allRoutees)
      logic.selectRoutee() should ===(refA)

      val bRemoved = Set(refA)
      logic.routeesUpdated(bRemoved)
      logic.selectRoutee() should ===(refA)
    }

    "move on to next when several removed" in {
      // this can happen with a group router update
      val refA = TestProbe("a").ref
      val refB = TestProbe("b").ref
      val refC = TestProbe("c").ref
      val refD = TestProbe("d").ref
      val allRoutees = Set(refA, refB, refC, refD)

      val logic = new RoutingLogics.RoundRobinLogic[Any]
      logic.routeesUpdated(allRoutees)
      logic.selectRoutee() should ===(refA)
      logic.selectRoutee() should ===(refB)

      val severalRemoved = Set(refA, refC)
      logic.routeesUpdated(severalRemoved)
      logic.selectRoutee() should ===(refC)
    }

    "wrap around when several removed" in {
      // this can happen with a group router update
      val refA = TestProbe("a").ref
      val refB = TestProbe("b").ref
      val refC = TestProbe("c").ref
      val refD = TestProbe("d").ref
      val allRoutees = Set(refA, refB, refC, refD)

      val logic = new RoutingLogics.RoundRobinLogic[Any]
      logic.routeesUpdated(allRoutees)
      logic.selectRoutee() should ===(refA)
      logic.selectRoutee() should ===(refB)
      logic.selectRoutee() should ===(refC)

      val severalRemoved = Set(refA, refC)
      logic.routeesUpdated(severalRemoved)
      logic.selectRoutee() should ===(refA)
    }

    "pick first in with a completely new set of routees" in {
      // this can happen with a group router update
      val refA = TestProbe("a").ref
      val refB = TestProbe("b").ref
      val refC = TestProbe("c").ref
      val refD = TestProbe("d").ref
      val initialRoutees = Set(refA, refB)

      val logic = new RoutingLogics.RoundRobinLogic[Any]
      logic.routeesUpdated(initialRoutees)
      logic.selectRoutee() should ===(refA)
      logic.selectRoutee() should ===(refB)
      logic.selectRoutee() should ===(refA)

      val severalRemoved = Set(refC, refD)
      logic.routeesUpdated(severalRemoved)
      logic.selectRoutee() should ===(refC)
    }

  }

  "The random logic" must {
    "select randomly" in {
      val refA = TestProbe("a").ref
      val refB = TestProbe("b").ref
      val refC = TestProbe("c").ref
      val refD = TestProbe("d").ref
      val routees = Set(refA, refB, refC, refD)

      val logic = new RoutingLogics.RandomLogic[Any]()
      logic.routeesUpdated(routees)

      (0 to 10).foreach { _ =>
        // not much to verify here, but let's exercise it at least
        val routee = logic.selectRoutee()
        routees should contain(routee)
      }

    }

  }
}
