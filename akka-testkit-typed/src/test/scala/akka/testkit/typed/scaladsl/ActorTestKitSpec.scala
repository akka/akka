/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit.typed.scaladsl

import akka.actor.typed.Terminated
import akka.actor.typed.scaladsl.Behaviors
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }

import scala.concurrent.Promise

// can be mixed into any spec style,
class ActorTestKitSpec extends WordSpec with Matchers with ActorTestKit with ScalaFutures {

  "the Scala testkit" should {

    "generate a default name from the test class" in {
      system.name should ===("ActorTestKitSpec")
    }

    "spawn an actor" in {
      val sawMessage = Promise[Boolean]()
      val ref = spawn(Behaviors.setup[AnyRef] { ctx ⇒
        sawMessage.trySuccess(true)
        Behaviors.empty
      })

      sawMessage.future.futureValue should ===(true)
    }

    "spawn a named actor" in {
      val spawnedWithName = Promise[String]()
      val ref = spawn(Behaviors.setup[AnyRef] { ctx ⇒
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

// derivate classes should also work fine (esp the naming part
trait MyBaseSpec extends WordSpec with ActorTestKit with Matchers with BeforeAndAfterAll {
  override protected def afterAll(): Unit = {
    shutdownTestKit()
  }
}

class MyConcreteDerivateSpec extends MyBaseSpec {
  "A derivate test" should {
    "generate a default name from the test class" in {
      system.name should ===("MyConcreteDerivateSpec")
    }
  }

}

