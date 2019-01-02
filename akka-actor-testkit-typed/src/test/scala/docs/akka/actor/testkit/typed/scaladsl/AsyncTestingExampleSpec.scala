/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.actor.testkit.typed.scaladsl

import akka.actor.Scheduler
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed._
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.util.Timeout
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Matchers
import org.scalatest.WordSpec
import scala.concurrent.duration._

import scala.concurrent.Future
import scala.util.Success
import scala.util.Try

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

  //#under-test-2
  case class Message(i: Int, replyTo: ActorRef[Try[Int]])

  class Producer(publisher: ActorRef[Message])(implicit scheduler: Scheduler) {

    def produce(messages: Int)(implicit timeout: Timeout): Unit = {
      (0 until messages).foreach(publish)
    }

    private def publish(i: Int)(implicit timeout: Timeout): Future[Try[Int]] = {
      publisher ? (ref ⇒ Message(i, ref))
    }

  }
  //#under-test-2

}

//#test-header
class AsyncTestingExampleSpec extends WordSpec with BeforeAndAfterAll with Matchers {
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

    "support observing mocked behavior" in {

      //#test-observe-mocked-behavior
      import testKit._

      // simulate the happy path
      val mockedBehavior = Behaviors.receiveMessage[Message] { msg ⇒
        msg.replyTo ! Success(msg.i)
        Behaviors.same
      }
      val probe = testKit.createTestProbe[Message]()
      val mockedPublisher = testKit.spawn(Behaviors.monitor(probe.ref, mockedBehavior))

      // test our component
      val producer = new Producer(mockedPublisher)
      val messages = 3
      producer.produce(messages)

      // verify expected behavior
      for (i ← 0 until messages) {
        val msg = probe.expectMessageType[Message]
        msg.i shouldBe i
      }
      //#test-observe-mocked-behavior
    }
  }

  //#test-shutdown
  override def afterAll(): Unit = testKit.shutdownTestKit()
  //#test-shutdown
}
