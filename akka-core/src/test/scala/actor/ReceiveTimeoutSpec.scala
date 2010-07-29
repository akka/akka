package se.scalablesolutions.akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test

import java.util.concurrent.TimeUnit
import org.multiverse.api.latches.StandardLatch
import Actor._

class ReceiveTimeoutSpec extends JUnitSuite {

  @Test def receiveShouldGetTimeout= {

    val timeoutLatch = new StandardLatch

    val timeoutActor = actorOf(new Actor {
      self.receiveTimeout = Some(500L)

      protected def receive = {
        case ReceiveTimeout => timeoutLatch.open
      }
    }).start

    assert(timeoutLatch.tryAwait(3, TimeUnit.SECONDS))
  }

  @Test def swappedReceiveShouldAlsoGetTimout = {
    val timeoutLatch = new StandardLatch

    val timeoutActor = actorOf(new Actor {
      self.receiveTimeout = Some(500L)

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
      self.receiveTimeout = Some(500L)

      protected def receive = {
        case Tick => ()
        case ReceiveTimeout => timeoutLatch.open
      }
    }).start
    timeoutActor ! Tick

    assert(timeoutLatch.tryAwait(2, TimeUnit.SECONDS) == false)
  }

  @Test def timeoutShouldNotBeSentWhenNotSpecified = {
    val timeoutLatch = new StandardLatch
    val timeoutActor = actorOf(new Actor {

      protected def receive = {
        case ReceiveTimeout => timeoutLatch.open
      }
    }).start

    assert(timeoutLatch.tryAwait(1, TimeUnit.SECONDS) == false)
  }
}
