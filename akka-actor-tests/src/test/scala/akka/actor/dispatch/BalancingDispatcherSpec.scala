package akka.actor.dispatch

import org.scalatest.matchers.MustMatchers
import org.scalatest.junit.JUnitSuite

import org.junit.Test

import java.util.concurrent.{ TimeUnit, CountDownLatch }
import akka.actor.Actor._
import akka.dispatch.{ MessageQueue, Dispatchers }
import akka.actor.{ LocalActorRef, IllegalActorStateException, Actor, Props }

object BalancingDispatcherSpec {

  def newWorkStealer() = Dispatchers.newBalancingDispatcher("pooled-dispatcher", 1).build

  val delayableActorDispatcher, sharedActorDispatcher, parentActorDispatcher = newWorkStealer()

  class DelayableActor(delay: Int, finishedCounter: CountDownLatch) extends Actor {
    @volatile
    var invocationCount = 0

    def receive = {
      case x: Int ⇒ {
        Thread.sleep(delay)
        invocationCount += 1
        finishedCounter.countDown()
      }
    }
  }

  class FirstActor extends Actor {
    def receive = { case _ ⇒ {} }
  }

  class SecondActor extends Actor {
    def receive = { case _ ⇒ {} }
  }

  class ParentActor extends Actor {
    def receive = { case _ ⇒ {} }
  }

  class ChildActor extends ParentActor {
  }
}

/**
 * @author Jan Van Besien
 */
class BalancingDispatcherSpec extends JUnitSuite with MustMatchers {
  import BalancingDispatcherSpec._

  @Test
  def fastActorShouldStealWorkFromSlowActor {
    val finishedCounter = new CountDownLatch(110)

    val slow = actorOf(Props(new DelayableActor(50, finishedCounter)).withDispatcher(delayableActorDispatcher), "slow").asInstanceOf[LocalActorRef]
    val fast = actorOf(Props(new DelayableActor(10, finishedCounter)).withDispatcher(delayableActorDispatcher), "fast").asInstanceOf[LocalActorRef]

    var sentToFast = 0

    for (i ← 1 to 100) {
      // send most work to slow actor
      if (i % 20 == 0) {
        fast ! i
        sentToFast += 1
      } else
        slow ! i
    }

    // now send some messages to actors to keep the dispatcher dispatching messages
    for (i ← 1 to 10) {
      Thread.sleep(150)
      if (i % 2 == 0) {
        fast ! i
        sentToFast += 1
      } else
        slow ! i
    }

    finishedCounter.await(5, TimeUnit.SECONDS)
    fast.underlying.mailbox.asInstanceOf[MessageQueue].isEmpty must be(true)
    slow.underlying.mailbox.asInstanceOf[MessageQueue].isEmpty must be(true)
    fast.underlyingActorInstance.asInstanceOf[DelayableActor].invocationCount must be > sentToFast
    fast.underlyingActorInstance.asInstanceOf[DelayableActor].invocationCount must be >
      (slow.underlyingActorInstance.asInstanceOf[DelayableActor].invocationCount)
    slow.stop()
    fast.stop()
  }

  @Test
  def canNotUseActorsOfDifferentTypesInSameDispatcher(): Unit = {
    val first = actorOf(Props[FirstActor].withDispatcher(sharedActorDispatcher))

    intercept[IllegalActorStateException] {
      actorOf(Props[SecondActor].withDispatcher(sharedActorDispatcher))
    }
    first.stop()
  }

  @Test
  def canNotUseActorsOfDifferentSubTypesInSameDispatcher(): Unit = {
    val parent = actorOf(Props[ParentActor].withDispatcher(parentActorDispatcher))

    intercept[IllegalActorStateException] {
      val child = actorOf(Props[ChildActor].withDispatcher(parentActorDispatcher))
      child.stop()
    }

    parent.stop()
  }
}
