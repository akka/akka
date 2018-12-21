/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.actor.testkit.typed.scaladsl

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.{ PostStop, _ }
import akka.actor.typed.scaladsl.Behaviors
import org.scalatest.BeforeAndAfterAll
import org.scalatest.WordSpec
import scala.concurrent.duration._

object AsyncTestingExampleSpec {
  //#under-test
  case class Ping(message: String, response: ActorRef[Pong])
  case class Pong(message: String)

  val echoActor: Behavior[Ping] = Behaviors.receive { (_, message) ⇒
    message match {
      case Ping(m, replyTo) ⇒
        replyTo ! Pong(m)
        Behaviors.same
    }
  }
  //#under-test
}

//#test-header
class AsyncTestingExampleSpec extends WordSpec with BeforeAndAfterAll {
  val testKit = ActorTestKit()
  //#test-header

  import AsyncTestingExampleSpec._

  "A testkit" must {
    "support verifying a response" in {
      //#test-spawn
      val pinger = testKit.spawn(echoActor, "ping")
      val probe = testKit.createTestProbe[Pong]()
      pinger ! Ping("hello", probe.ref)
      probe.expectMessage(Pong("hello"))
      //#test-spawn
    }

    "support verifying a response - anonymous" in {
      //#test-spawn-anonymous
      val pinger = testKit.spawn(echoActor)
      //#test-spawn-anonymous
      val probe = testKit.createTestProbe[Pong]()
      pinger ! Ping("hello", probe.ref)
      probe.expectMessage(Pong("hello"))
    }

    "be able to stop actors under test" in {
      // Will fail with 'name not unique' exception if the first actor is not fully stopped
      val probe = testKit.createTestProbe[Pong]()
      //#test-stop-actors
      val pinger1 = testKit.spawn(echoActor, "pinger")
      pinger1 ! Ping("hello", probe.ref)
      probe.expectMessage(Pong("hello"))
      testKit.stop(pinger1) // Uses default timeout

      // Immediately creating an actor with the same name
      val pinger2 = testKit.spawn(echoActor, "pinger")
      pinger2 ! Ping("hello", probe.ref)
      probe.expectMessage(Pong("hello"))
      testKit.stop(pinger2, 10.seconds) // Custom timeout
      //#test-stop-actors
    }
  }

  //#test-shutdown
  override def afterAll(): Unit = testKit.shutdownTestKit()
  //#test-shutdown
}
