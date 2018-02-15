/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.testkit.typed.scaladsl

import akka.Done
import akka.actor.typed.Terminated
import akka.actor.typed.scaladsl.Behaviors
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }

import scala.concurrent.Promise

// can be mixed into any spec style,
class TestKitSpec extends WordSpec with Matchers with TestKit with ScalaFutures {

  "the Scala testkit" should {

    "generate a default name from the test class" in {
      system.name should ===("TestKitSpec")
    }

    "spawn an actor" in {
      val sawMessage = Promise[Boolean]()
      val ref = spawn(Behaviors.deferred[AnyRef] { ctx ⇒
        sawMessage.trySuccess(true)
        Behaviors.empty
      })

      sawMessage.future.futureValue should ===(true)
    }

    "spawn a named actor" in {
      val spawnedWithName = Promise[String]()
      val ref = spawn(Behaviors.deferred[AnyRef] { ctx ⇒
        spawnedWithName.trySuccess(ctx.self.path.name)
        Behaviors.empty
      }, "name")

      spawnedWithName.future.futureValue should ===("name")
    }

    "stop the actor system" in {
      // usually done in test framework hook method but we want to assert
      shutdownTestKit()
      system.whenTerminated.futureValue shouldBe a[Terminated]
    }
  }

}

