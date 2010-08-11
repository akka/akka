package se.scalablesolutions.akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test

import java.util.concurrent.TimeUnit
import org.multiverse.api.latches.StandardLatch
import UntypedActor._

object UntypedActorReceiveTimeoutSpec {
  object Tick
  class TestReceiveTimeoutActor extends UntypedActor {
    val latch = new StandardLatch

    def onReceive(message: Any):Unit = message match {
      case ReceiveTimeout => latch.open
      case Tick =>
    }
  }
  
  class FiveOhOhTestReceiveTimeoutActor extends TestReceiveTimeoutActor {
    getContext.setReceiveTimeout(500L)
  }
}

class UntypedActorReceiveTimeoutSpec extends JUnitSuite {
  import UntypedActorReceiveTimeoutSpec._
  
  @Test def receiveShouldGetTimeout = {
    val timeoutActor = actorOf(classOf[FiveOhOhTestReceiveTimeoutActor]).start

    assert(timeoutActor.actorRef.actor.asInstanceOf[TestReceiveTimeoutActor].latch.tryAwait(3, TimeUnit.SECONDS))
  }

  @Test def swappedReceiveShouldAlsoGetTimout = {
    val timeoutActor = actorOf(classOf[FiveOhOhTestReceiveTimeoutActor]).start

    assert(timeoutActor.actorRef.actor.asInstanceOf[TestReceiveTimeoutActor].latch.tryAwait(3, TimeUnit.SECONDS))

    val swappedLatch = new StandardLatch
    timeoutActor sendOneWay HotSwap(Some{
      case ReceiveTimeout => swappedLatch.open
    })

    assert(swappedLatch.tryAwait(3, TimeUnit.SECONDS))
  }

  @Test def timeoutShouldBeCancelledAfterRegularReceive = {
    val timeoutActor = actorOf(classOf[FiveOhOhTestReceiveTimeoutActor]).start
    timeoutActor sendOneWay Tick
    assert(timeoutActor.actorRef.actor.asInstanceOf[TestReceiveTimeoutActor].latch.tryAwait(1, TimeUnit.SECONDS) == false)
  }

  @Test def timeoutShouldNotBeSentWhenNotSpecified = {
    val timeoutLatch = new StandardLatch
    val timeoutActor = actorOf(classOf[TestReceiveTimeoutActor]).start
    assert(timeoutLatch.tryAwait(2, TimeUnit.SECONDS) == false)
  }
}
