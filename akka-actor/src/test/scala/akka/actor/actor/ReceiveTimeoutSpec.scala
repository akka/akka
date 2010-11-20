package akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test

import java.util.concurrent.TimeUnit
import org.multiverse.api.latches.StandardLatch
import Actor._
import java.util.concurrent.atomic.AtomicInteger

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
    timeoutActor.stop
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
    timeoutActor ! HotSwap(self => {
      case ReceiveTimeout => swappedLatch.open
    })

    assert(swappedLatch.tryAwait(3, TimeUnit.SECONDS))
    timeoutActor.stop
  }

  @Test def timeoutShouldBeRescheduledAfterRegularReceive = {

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

    assert(timeoutLatch.tryAwait(2, TimeUnit.SECONDS) == true)
    timeoutActor.stop
  }

  @Test def timeoutShouldBeTurnedOffIfDesired = {
    val count = new AtomicInteger(0)
    val timeoutLatch = new StandardLatch
    case object Tick
    val timeoutActor = actorOf(new Actor {
      self.receiveTimeout = Some(500L)

      protected def receive = {
        case Tick => ()
        case ReceiveTimeout =>
          timeoutLatch.open
          count.incrementAndGet
          self.receiveTimeout = None
      }
    }).start
    timeoutActor ! Tick

    assert(timeoutLatch.tryAwait(2, TimeUnit.SECONDS) == true)
    assert(count.get === 1)
    timeoutActor.stop
  }

  @Test def timeoutShouldNotBeSentWhenNotSpecified = {
    val timeoutLatch = new StandardLatch
    val timeoutActor = actorOf(new Actor {

      protected def receive = {
        case ReceiveTimeout => timeoutLatch.open
      }
    }).start

    assert(timeoutLatch.tryAwait(1, TimeUnit.SECONDS) == false)
    timeoutActor.stop
  }
}
