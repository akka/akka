/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit.typed.scaladsl

import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import org.scalatest.{ BeforeAndAfterAll, WordSpec }

object AsyncTestingExampleSpec {
  //#under-test
  case class Ping(msg: String, response: ActorRef[Pong])
  case class Pong(msg: String)

  val echoActor = Behaviors.receive[Ping] { (_, msg) ⇒
    msg match {
      case Ping(m, replyTo) ⇒
        replyTo ! Pong(m)
        Behaviors.same
    }
  }
  //#under-test
}

//#test-header
class AsyncTestingExampleSpec extends WordSpec with ActorTestKit with BeforeAndAfterAll {
  //#test-header

  import AsyncTestingExampleSpec._

  "A testkit" must {
    "support verifying a response" in {
      //#test-spawn
      val probe = TestProbe[Pong]()
      val pinger = spawn(echoActor, "ping")
      pinger ! Ping("hello", probe.ref)
      probe.expectMessage(Pong("hello"))
      //#test-spawn
    }

    "support verifying a response - anonymous" in {
      //#test-spawn-anonymous
      val probe = TestProbe[Pong]()
      val pinger = spawn(echoActor)
      pinger ! Ping("hello", probe.ref)
      probe.expectMessage(Pong("hello"))
      //#test-spawn-anonymous
    }
  }

  //#test-shutdown
  override def afterAll(): Unit = shutdownTestKit()
  //#test-shutdown
}
