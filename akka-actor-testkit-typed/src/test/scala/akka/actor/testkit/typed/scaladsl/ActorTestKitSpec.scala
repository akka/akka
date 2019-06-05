/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.scaladsl

import akka.Done

import scala.concurrent.Promise
import akka.actor.typed.scaladsl.Behaviors
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Matchers
import org.scalatest.WordSpec
import org.scalatest.WordSpecLike

class ActorTestKitSpec extends ScalaTestWithActorTestKit with WordSpecLike {

  "the Scala testkit" should {

    "generate a default name from the test class via ScalaTestWithActorTestKit" in {
      system.name should ===("ActorTestKitSpec")
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
  }

}

// derivative classes should also work fine (esp the naming part
abstract class MyBaseSpec extends ScalaTestWithActorTestKit with Matchers with WordSpecLike

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

class CompositionSpec extends WordSpec with Matchers with BeforeAndAfterAll {
  val testKit = ActorTestKit()

  override def afterAll(): Unit = {
    testKit.shutdownTestKit()
  }

  "generate a default name from the test class" in {
    testKit.system.name should ===("CompositionSpec")
  }
}
