/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.routing
import akka.actor.testkit.typed.scaladsl.{ LogCapturing, ScalaTestWithActorTestKit, TestProbe }
import akka.actor.typed.internal.routing.RoutingLogics.ConsistentHashingLogic
import akka.actor.typed.internal.routing.RoutingLogics.ConsistentHashingLogic.ConsistentHashMapping
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorSystem, Behavior }
import org.scalatest.{ Matchers, WordSpecLike }

class RoutingLogicSpec extends ScalaTestWithActorTestKit with WordSpecLike with Matchers with LogCapturing {

  val emptyMessage: Any = ""

  "The round robin routing logic" must {

    "round robin" in {
      val refA = TestProbe("a").ref
      val refB = TestProbe("b").ref
      val refC = TestProbe("c").ref
      val allRoutees = Set(refA, refB, refC)

      val logic = new RoutingLogics.RoundRobinLogic[Any]

      logic.routeesUpdated(allRoutees)
      logic.selectRoutee(emptyMessage) should ===(refA)
      logic.selectRoutee(emptyMessage) should ===(refB)
      logic.selectRoutee(emptyMessage) should ===(refC)
      logic.selectRoutee(emptyMessage) should ===(refA)
    }

    "not skip one on removal" in {
      val refA = TestProbe("a").ref
      val refB = TestProbe("b").ref
      val refC = TestProbe("c").ref
      val allRoutees = Set(refA, refB, refC)

      val logic = new RoutingLogics.RoundRobinLogic[Any]
      logic.routeesUpdated(allRoutees)
      logic.selectRoutee(emptyMessage) should ===(refA)
      logic.selectRoutee(emptyMessage) should ===(refB)

      val bRemoved = Set(refA, refC)
      logic.routeesUpdated(bRemoved)
      logic.selectRoutee(emptyMessage) should ===(refC)
    }

    "handle last one removed" in {
      val refA = TestProbe("a").ref
      val refB = TestProbe("b").ref
      val allRoutees = Set(refA, refB)

      val logic = new RoutingLogics.RoundRobinLogic[Any]
      logic.routeesUpdated(allRoutees)
      logic.selectRoutee(emptyMessage) should ===(refA)

      val bRemoved = Set(refA)
      logic.routeesUpdated(bRemoved)
      logic.selectRoutee(emptyMessage) should ===(refA)
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
      logic.selectRoutee(emptyMessage) should ===(refA)
      logic.selectRoutee(emptyMessage) should ===(refB)

      val severalRemoved = Set(refA, refC)
      logic.routeesUpdated(severalRemoved)
      logic.selectRoutee(emptyMessage) should ===(refC)
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
      logic.selectRoutee(emptyMessage) should ===(refA)
      logic.selectRoutee(emptyMessage) should ===(refB)
      logic.selectRoutee(emptyMessage) should ===(refC)

      val severalRemoved = Set(refA, refC)
      logic.routeesUpdated(severalRemoved)
      logic.selectRoutee(emptyMessage) should ===(refA)
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
      logic.selectRoutee(emptyMessage) should ===(refA)
      logic.selectRoutee(emptyMessage) should ===(refB)
      logic.selectRoutee(emptyMessage) should ===(refA)

      val severalRemoved = Set(refC, refD)
      logic.routeesUpdated(severalRemoved)
      logic.selectRoutee(emptyMessage) should ===(refC)
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
        val routee = logic.selectRoutee(emptyMessage)
        routees should contain(routee)
      }

    }

  }

  "The consistent hashing logic" must {
    val behavior: Behavior[Int] = Behaviors.empty[Int]
    val typedSystem: ActorSystem[Int] = ActorSystem(behavior, "emptySystem")
    val modulo10Mapping: ConsistentHashMapping[Int] = { case in: Int => (in % 10).toString }
    val messages: Map[Any, Seq[Int]] = (1 to 1000).groupBy(modulo10Mapping)

    "not accept virtualization factor lesser than 1" in {
      val caught = intercept[IllegalArgumentException] {
        new RoutingLogics.ConsistentHashingLogic[Int](0, ConsistentHashingLogic.emptyHashMapping, typedSystem)
      }
      caught.getMessage shouldEqual "virtualNodesFactor must be >= 1"
    }

    "not accept null actor system" in {
      val caught = intercept[IllegalArgumentException] {
        new RoutingLogics.ConsistentHashingLogic[String](2, ConsistentHashingLogic.emptyHashMapping, null)
      }
      caught.getMessage shouldEqual "requirement failed: system argument of ConsistentHashingLogic cannot be null."
    }

    "return deadLetters when there are no routees" in {
      val logic =
        new RoutingLogics.ConsistentHashingLogic[Int](1, modulo10Mapping, typedSystem)
      logic.selectRoutee(0) shouldBe typedSystem.deadLetters
    }

    "hash consistently" in {
      consitentHashingTestWithVirtualizationFactor(1)
    }

    "hash consistently with virtualization factor" in {
      consitentHashingTestWithVirtualizationFactor(13)
    }

    "hash consistently when several new added" in {
      val logic =
        new RoutingLogics.ConsistentHashingLogic[Int](2, modulo10Mapping, typedSystem)
      val refA = TestProbe("a").ref
      val refB = TestProbe("b").ref
      val refC = TestProbe("c").ref
      val refD = TestProbe("d").ref
      logic.routeesUpdated(Set(refA, refB, refC, refD))
      // every group should have the same actor ref
      verifyConsistentHashing(logic)
      logic.routeesUpdated(Set(refA, refB))
      verifyConsistentHashing(logic)
    }

    "hash consistently when several new removed" in {
      val logic =
        new RoutingLogics.ConsistentHashingLogic[Int](2, modulo10Mapping, typedSystem)
      val refA = TestProbe("a").ref
      val refB = TestProbe("b").ref
      val refC = TestProbe("c").ref
      val refD = TestProbe("d").ref
      logic.routeesUpdated(Set(refA, refB))
      // every group should have the same actor ref
      verifyConsistentHashing(logic)
      logic.routeesUpdated(Set(refA, refB, refC, refD))
      verifyConsistentHashing(logic)
    }

    def consitentHashingTestWithVirtualizationFactor(virtualizationFactor: Int): Boolean = {
      val logic =
        new RoutingLogics.ConsistentHashingLogic[Int](virtualizationFactor, modulo10Mapping, typedSystem)
      val refA = TestProbe("a").ref
      val refB = TestProbe("b").ref
      val refC = TestProbe("c").ref
      val refD = TestProbe("d").ref
      logic.routeesUpdated(Set(refA, refB, refC, refD))
      verifyConsistentHashing(logic)
    }

    def verifyConsistentHashing(logic: ConsistentHashingLogic[Int]): Boolean = {
      messages.view.map(_._2.map(logic.selectRoutee)).forall {
        refs => refs.headOption.forall(head => refs.forall(_ == head))
      }
    }

  }
}
