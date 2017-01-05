/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
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
  case object TransperentTick extends NotInfluenceReceiveTimeout
}

class ReceiveTimeoutSpec extends AkkaSpec {
  import ReceiveTimeoutSpec._

  "An actor with receive timeout" must {

    "get timeout" taggedAs TimingTest in {
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

    "reschedule timeout after regular receive" taggedAs TimingTest in {
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

    "be able to turn off timeout if desired" taggedAs TimingTest in {
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
      count.get should ===(1)
      system.stop(timeoutActor)
    }

    "not receive timeout message when not specified" taggedAs TimingTest in {
      val timeoutLatch = TestLatch()

      val timeoutActor = system.actorOf(Props(new Actor {
        def receive = {
          case ReceiveTimeout ⇒ timeoutLatch.open
        }
      }))

      intercept[TimeoutException] { Await.ready(timeoutLatch, 1 second) }
      system.stop(timeoutActor)
    }

    "get timeout while receiving NotInfluenceReceiveTimeout messages" taggedAs TimingTest in {
      val timeoutLatch = TestLatch()

      val timeoutActor = system.actorOf(Props(new Actor {
        context.setReceiveTimeout(1 second)

        def receive = {
          case ReceiveTimeout  ⇒ timeoutLatch.open
          case TransperentTick ⇒
        }
      }))

      val ticks = system.scheduler.schedule(100.millis, 100.millis, new Runnable {
        override def run() = {
          timeoutActor ! TransperentTick
          timeoutActor ! Identify(None)
        }
      })(system.dispatcher)

      Await.ready(timeoutLatch, TestLatch.DefaultTimeout)
      ticks.cancel()
      system.stop(timeoutActor)
    }
  }
}
