/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import language.postfixOps

import akka.testkit._
import akka.util.duration._

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

        def receive = {
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

        def receive = {
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

        def receive = {
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
        def receive = {
          case ReceiveTimeout ⇒ timeoutLatch.open
        }
      }))

      intercept[TimeoutException] { Await.ready(timeoutLatch, 1 second) }
      system.stop(timeoutActor)
    }
  }
}
