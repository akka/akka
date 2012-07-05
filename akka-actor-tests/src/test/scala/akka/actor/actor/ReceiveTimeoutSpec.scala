/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import akka.testkit._
import akka.util.duration._

import Actor._
import java.util.concurrent.atomic.AtomicInteger

class ReceiveTimeoutSpec extends WordSpec with MustMatchers {
  import Actor._

  "An actor with receive timeout" must {

    "get timeout" in {
      val timeoutLatch = TestLatch()

      val timeoutActor = actorOf(new Actor {
        self.receiveTimeout = Some(500L)

        protected def receive = {
          case ReceiveTimeout ⇒ timeoutLatch.open
        }
      }).start()

      timeoutLatch.await
      timeoutActor.stop()
    }

    "get timeout when swapped" in {
      val timeoutLatch = TestLatch()

      val timeoutActor = actorOf(new Actor {
        self.receiveTimeout = Some(500L)

        protected def receive = {
          case ReceiveTimeout ⇒ timeoutLatch.open
        }
      }).start()

      timeoutLatch.await

      val swappedLatch = TestLatch()

      timeoutActor ! HotSwap(self ⇒ {
        case ReceiveTimeout ⇒ swappedLatch.open
      })

      swappedLatch.await
      timeoutActor.stop()
    }

    "reschedule timeout after regular receive" in {
      val timeoutLatch = TestLatch()
      case object Tick

      val timeoutActor = actorOf(new Actor {
        self.receiveTimeout = Some(500L)

        protected def receive = {
          case Tick           ⇒ ()
          case ReceiveTimeout ⇒ timeoutLatch.open
        }
      }).start()

      timeoutActor ! Tick

      timeoutLatch.await
      timeoutActor.stop()
    }

    "be able to turn off timeout if desired" in {
      val count = new AtomicInteger(0)
      val timeoutLatch = TestLatch()
      case object Tick

      val timeoutActor = actorOf(new Actor {
        self.receiveTimeout = Some(500L)

        protected def receive = {
          case Tick ⇒ ()
          case ReceiveTimeout ⇒
            count.incrementAndGet
            timeoutLatch.open
            self.receiveTimeout = None
        }
      }).start()

      timeoutActor ! Tick

      timeoutLatch.await
      count.get must be(1)
      timeoutActor.stop()
    }

    "not receive timeout message when not specified" in {
      val timeoutLatch = TestLatch()

      val timeoutActor = actorOf(new Actor {
        protected def receive = {
          case ReceiveTimeout ⇒ timeoutLatch.open
        }
      }).start()

      timeoutLatch.awaitTimeout(1 second) // timeout expected
      timeoutActor.stop()
    }

    "have ReceiveTimeout eq to Actors ReceiveTimeout" in {
      akka.actor.Actors.receiveTimeout() must be theSameInstanceAs (ReceiveTimeout)
    }
  }
}
