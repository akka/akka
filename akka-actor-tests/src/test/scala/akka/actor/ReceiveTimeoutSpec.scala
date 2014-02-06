/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import language.postfixOps
import akka.testkit._
import scala.concurrent.duration._
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import java.util.concurrent.TimeoutException

object ReceiveTimeoutSpec {
  case object Tick
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ReceiveTimeoutSpec extends AkkaSpec {
  import ReceiveTimeoutSpec._

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

      val timeoutActor = system.actorOf(Props(new Actor {
        context.setReceiveTimeout(500 milliseconds)

        def receive = {
          case Tick ⇒ ()
          case ReceiveTimeout ⇒
            count.incrementAndGet
            timeoutLatch.open
            context.setReceiveTimeout(Duration.Undefined)
        }
      }))

      timeoutActor ! Tick

      Await.ready(timeoutLatch, TestLatch.DefaultTimeout)
      count.get should be(1)
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
