/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern

import akka.actor._
import akka.testkit.{ AkkaSpec, ImplicitSender, TestProbe }

import scala.concurrent.Promise
import scala.concurrent.duration._

object PromiseRefSpec {
  case class Request(replyTo: ActorRef)
  case object Response

  case object FirstMessage
  case object SecondMessage
}

class PromiseRefSpec extends AkkaSpec with ImplicitSender {
  import PromiseRefSpec._
  import akka.pattern._

  "The PromiseRef" must {
    "complete promise with received message" in {
      val promiseRef = PromiseRef(5.seconds)

      val target = system.actorOf(Props(new Actor {
        def receive = { case Request(replyTo) => replyTo ! Response }
      }))

      target ! Request(promiseRef.ref)
      promiseRef.future.futureValue should ===(Response)
    }

    "throw IllegalArgumentException on negative timeout" in {
      intercept[IllegalArgumentException] {
        PromiseRef(-5.seconds)
      }
    }

    "receive only one message" in {
      val deadListener = TestProbe()
      system.eventStream.subscribe(deadListener.ref, classOf[DeadLetter])

      val promiseRef = PromiseRef(5.seconds)

      promiseRef.ref ! FirstMessage
      promiseRef.future.futureValue should ===(FirstMessage)

      promiseRef.ref ! SecondMessage
      deadListener.expectMsgType[DeadLetter].message should ===(SecondMessage)
    }

    "work with explicitly constructed PromiseRef's" in {
      val promise = Promise[Int]()

      val alice = system.actorOf(Props(new Actor {
        def receive = { case Response => promise.success(42) }
      }))

      val promiseRef = PromiseRef.wrap(alice, promise)

      val bob = system.actorOf(Props(new Actor {
        def receive = { case Request(replyTo) => replyTo ! Response }
      }))

      bob ! Request(promiseRef.ref)
      promiseRef.future.futureValue should ===(42)
    }
  }
}
