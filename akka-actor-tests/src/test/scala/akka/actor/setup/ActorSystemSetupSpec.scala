/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.setup

import akka.actor.ActorSystem
import akka.testkit.TestKit
import org.scalatest.{ Matchers, WordSpec }

case class DummySetup(name: String) extends Setup
case class DummySetup2(name: String) extends Setup
case class DummySetup3(name: String) extends Setup

class ActorSystemSetupSpec extends WordSpec with Matchers {

  "The ActorSystemSettings" should {

    "store and retrieve a setup" in {
      val setup = DummySetup("Al Dente")
      val setups = ActorSystemSetup().withSetup(setup)

      setups.get[DummySetup] should ===(Some(setup))
      setups.get[DummySetup2] should ===(None)
    }

    "replace setup if already defined" in {
      val setup1 = DummySetup("Al Dente")
      val setup2 = DummySetup("Earl E. Bird")
      val setups = ActorSystemSetup().withSetup(setup1).withSetup(setup2)

      setups.get[DummySetup] should ===(Some(setup2))
    }

    "provide a fluent creation alternative" in {
      val a = DummySetup("Al Dente")
      val b = DummySetup("Earl E. Bird") // same type again
      val c = DummySetup2("Amanda Reckonwith")
      val setups = a and b and c

      setups.get[DummySetup] should ===(Some(b))
      setups.get[DummySetup2] should ===(Some(c))
    }

    "be created with a set of setups" in {
      val setup1 = DummySetup("Manny Kin")
      val setup2 = DummySetup2("Pepe Roni")
      val setups = ActorSystemSetup(setup1, setup2)

      setups.get[DummySetup].isDefined shouldBe true
      setups.get[DummySetup2].isDefined shouldBe true
      setups.get[DummySetup3].isDefined shouldBe false
    }

    "be available from the ExtendedActorSystem" in {
      var system: ActorSystem = null
      try {
        val setup = DummySetup("Tad Moore")
        system = ActorSystem("name", ActorSystemSetup(setup))

        system.settings.setup.get[DummySetup] should ===(Some(setup))

      } finally {
        TestKit.shutdownActorSystem(system)
      }
    }
  }

}
