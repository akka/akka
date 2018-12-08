/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.actor.testkit.typed.scaladsl

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import org.scalatest.BeforeAndAfterAll
import org.scalatest.WordSpec

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
  }

  //#test-shutdown
  override def afterAll(): Unit = testKit.shutdownTestKit()
  //#test-shutdown
}
