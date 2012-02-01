/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.testkit._
import scala.util.duration._

import java.util.concurrent.atomic.AtomicInteger
import akka.dispatch.Await
import java.util.concurrent.TimeoutException

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ReceiveTimeoutSpec extends AkkaSpec {

  "An actor with receive timeout" must {

    "get timeout" in {
      val timeoutLatch = TestLatch()

      val timeoutActor = system.actorOf(Props(new Actor {
        context.setReceiveTimeout(500 milliseconds)

        protected def receive = {
          case ReceiveTimeout ⇒ timeoutLatch.open
        }
      }))

      Await.ready(timeoutLatch, TestLatch.DefaultTimeout)
      system.stop(timeoutActor)
    }

    "reschedule timeout after regular receive" in {
      val timeoutLatch = TestLatch()
      case object Tick

      val timeoutActor = system.actorOf(Props(new Actor {
        context.setReceiveTimeout(500 milliseconds)

        protected def receive = {
          case Tick           ⇒ ()
          case ReceiveTimeout ⇒ timeoutLatch.open
        }
      }))

      timeoutActor ! Tick

      Await.ready(timeoutLatch, TestLatch.DefaultTimeout)
      system.stop(timeoutActor)
    }

    "be able to turn off timeout if desired" in {
      val count = new AtomicInteger(0)
      val timeoutLatch = TestLatch()
      case object Tick

      val timeoutActor = system.actorOf(Props(new Actor {
        context.setReceiveTimeout(500 milliseconds)

        protected def receive = {
          case Tick ⇒ ()
          case ReceiveTimeout ⇒
            count.incrementAndGet
            timeoutLatch.open
            context.resetReceiveTimeout()
        }
      }))

      timeoutActor ! Tick

      Await.ready(timeoutLatch, TestLatch.DefaultTimeout)
      count.get must be(1)
      system.stop(timeoutActor)
    }

    "not receive timeout message when not specified" in {
      val timeoutLatch = TestLatch()

      val timeoutActor = system.actorOf(Props(new Actor {
        protected def receive = {
          case ReceiveTimeout ⇒ timeoutLatch.open
        }
      }))

      intercept[TimeoutException] { Await.ready(timeoutLatch, 1 second) }
      system.stop(timeoutActor)
    }

    "have ReceiveTimeout eq to Actors ReceiveTimeout" in {
      akka.actor.Actors.receiveTimeout must be theSameInstanceAs (ReceiveTimeout)
    }
  }
}
