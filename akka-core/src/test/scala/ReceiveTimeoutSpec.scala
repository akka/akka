package se.scalablesolutions.akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test

import Actor._
import java.util.concurrent.TimeUnit
import org.multiverse.api.latches.StandardLatch

class ReceiveTimeoutSpec extends JUnitSuite {

  @Test def receiveShouldGetTimeout= {

    val timeoutLatch = new StandardLatch

    val timeoutActor = actorOf(new Actor {
      self.receiveTimeout = 500

      protected def receive = {
        case ReceiveTimeout => timeoutLatch.open
      }
    }).start

    assert(timeoutLatch.tryAwait(3, TimeUnit.SECONDS))
  }

  @Test def swappedReceiveShouldAlsoGetTimout = {
    val timeoutLatch = new StandardLatch

    val timeoutActor = actorOf(new Actor {
      self.receiveTimeout = 500

      protected def receive = {
        case ReceiveTimeout => timeoutLatch.open
      }
    }).start

    // after max 1 second the timeout should already been sent
    assert(timeoutLatch.tryAwait(3, TimeUnit.SECONDS))

    val swappedLatch = new StandardLatch
    timeoutActor ! HotSwap(Some{
      case ReceiveTimeout => swappedLatch.open
    })

    assert(swappedLatch.tryAwait(3, TimeUnit.SECONDS))
  }

  @Test def timeoutShouldBeCancelledAfterRegularReceive = {

    val timeoutLatch = new StandardLatch
    case object Tick
    val timeoutActor = actorOf(new Actor {
      self.receiveTimeout = 500

      protected def receive = {
        case Tick => ()
        case ReceiveTimeout => timeoutLatch.open
      }
    }).start
    timeoutActor ! Tick

    assert(timeoutLatch.tryAwait(3, TimeUnit.SECONDS) == false)
  }
}
