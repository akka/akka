/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.scaladsl

import akka.Done
import akka.actor.BootstrapSetup
import akka.actor.setup.ActorSystemSetup
import akka.actor.testkit.typed.internal.ActorTestKitGuardian
import akka.actor.typed.ActorSystem

import scala.concurrent.Promise
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.{AnyWordSpec, AnyWordSpecLike}

class ActorTestKitSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with LogCapturing {

  "the Scala testkit" should {

    "generate a default name from the test class via ScalaTestWithActorTestKit" in {
      system.name should ===("ActorTestKitSpec")
    }

    "generate a default name & ActorSystemSetup from the provided actor" in {
      val config = ConfigFactory.parseString("test.specific-config = yes")
      val system = ActorSystem(ActorTestKitGuardian.testKitGuardian, "TestActor", config)
      val testkit2 = ActorTestKit(system)
      try {
        testkit2.system.name should ===("TestActor")
        testkit2.system.settings.config.getString("test.specific-config") should ===("yes")
      } finally testkit2.shutdownTestKit()
    }

    "generate a default name and load the ActorSystemSetup from the provided ActorSystemSetup" in {
      val actorSystemSetup = ActorSystemSetup(BootstrapSetup(ConfigFactory.parseString("test.specific-config = yes")))
      val testkit2 = ActorTestKit("TestActor", actorSystemSetup)
      try {
        testkit2.system.name should ===("TestActor")
        testkit2.system.settings.config.getString("test.specific-config") should ===("yes")
      } finally testkit2.shutdownTestKit()
    }

    "generate a default name from the test class" in {
      val testkit2 = ActorTestKit()
      try {
        testkit2.system.name should ===("ActorTestKitSpec")
      } finally testkit2.shutdownTestKit()
    }

    "use name from given class name" in {
      val testkit2 = ActorTestKit(classOf[Vector[_]].getName)
      try {
        // removing package name and such
        testkit2.system.name should ===("Vector")
      } finally testkit2.shutdownTestKit()
    }

    "spawn an actor" in {
      val sawMessage = Promise[Boolean]()
      spawn(Behaviors.setup[AnyRef] { _ =>
        sawMessage.trySuccess(true)
        Behaviors.empty
      })

      sawMessage.future.futureValue should ===(true)
    }

    "spawn a named actor" in {
      val spawnedWithName = Promise[String]()
      spawn(Behaviors.setup[AnyRef] { context =>
        spawnedWithName.trySuccess(context.self.path.name)
        Behaviors.empty
      }, "name")

      spawnedWithName.future.futureValue should ===("name")
    }

    "stop the actor system" in {
      // usually done in test framework hook method but we want to assert
      val testkit2 = ActorTestKit()
      testkit2.shutdownTestKit()
      testkit2.system.whenTerminated.futureValue shouldBe a[Done]
    }

    "load application-test.conf by default" in {
      testKit.system.settings.config.getString("test.from-application-test") should ===("yes")
      testKit.system.settings.config.hasPath("test.from-application") should ===(false)
    }

    "not load application-test.conf if specific Config given" in {
      val testKit2 = ActorTestKit(ConfigFactory.parseString("test.specific-config = yes"))
      testKit2.system.settings.config.getString("test.specific-config") should ===("yes")
      testKit2.system.settings.config.hasPath("test.from-application-test") should ===(false)
      testKit2.system.settings.config.hasPath("test.from-application") should ===(false)

      // same if via ScalaTestWithActorTestKit
      val scalaTestWithActorTestKit2 = new ScalaTestWithActorTestKit("test.specific-config = yes") {}
      scalaTestWithActorTestKit2.system.settings.config.hasPath("test.from-application-test") should ===(false)
      scalaTestWithActorTestKit2.testKit.system.settings.config.hasPath("test.from-application") should ===(false)
    }

    "have unique names for probes across untyped testkit" in {
      import akka.actor.typed.scaladsl.adapter._
      createTestProbe()
      akka.testkit.TestProbe()(system.toClassic)
      // not throw
    }

  }

}

// derivative classes should also work fine (esp the naming part
abstract class MyBaseSpec extends ScalaTestWithActorTestKit with Matchers with AnyWordSpecLike with LogCapturing

class MyConcreteDerivateSpec extends MyBaseSpec {
  "A derivative test" should {
    "generate a default name from the test class via ScalaTestWithActorTestKit" in {
      system.name should ===("MyConcreteDerivateSpec")
    }

    "generate a default name from the test class" in {
      val testkit2 = ActorTestKit()
      try {
        testkit2.system.name should ===("MyConcreteDerivateSpec")
      } finally testkit2.shutdownTestKit()
    }

    "use name from given class name" in {
      val testkit2 = ActorTestKit(classOf[Vector[_]].getName)
      try {
        testkit2.system.name should ===("Vector")
      } finally testkit2.shutdownTestKit()
    }
  }

}

class CompositionSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll with LogCapturing {
  val testKit = ActorTestKit()

  override def afterAll(): Unit = {
    testKit.shutdownTestKit()
  }

  "generate a default name from the test class" in {
    testKit.system.name should ===("CompositionSpec")
  }
}
