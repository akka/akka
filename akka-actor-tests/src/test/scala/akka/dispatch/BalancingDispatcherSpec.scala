package akka.actor.dispatch

import org.scalatest.matchers.MustMatchers
import org.scalatest.junit.JUnitSuite

import org.junit.Test

import java.util.concurrent.{ TimeUnit, CountDownLatch }
import akka.actor.{ IllegalActorStateException, Actor }
import Actor._
import akka.dispatch.{ MessageQueue, Dispatchers }

object BalancingDispatcherSpec {

  def newWorkStealer() = Dispatchers.newBalancingDispatcher("pooled-dispatcher", 1).build

  val delayableActorDispatcher, sharedActorDispatcher, parentActorDispatcher = newWorkStealer()

  class DelayableActor(delay: Int, finishedCounter: CountDownLatch) extends Actor {
    self.dispatcher = delayableActorDispatcher
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
    self.dispatcher = sharedActorDispatcher
    def receive = { case _ ⇒ {} }
  }

  class SecondActor extends Actor {
    self.dispatcher = sharedActorDispatcher
    def receive = { case _ ⇒ {} }
  }

  class ParentActor extends Actor {
    self.dispatcher = parentActorDispatcher
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

    val slow = actorOf(new DelayableActor(50, finishedCounter), "slow").start
    val fast = actorOf(new DelayableActor(10, finishedCounter), "fast").start

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
    fast.mailbox.asInstanceOf[MessageQueue].isEmpty must be(true)
    slow.mailbox.asInstanceOf[MessageQueue].isEmpty must be(true)
    fast.actor.asInstanceOf[DelayableActor].invocationCount must be > sentToFast
    fast.actor.asInstanceOf[DelayableActor].invocationCount must be >
      (slow.actor.asInstanceOf[DelayableActor].invocationCount)
    slow.stop()
    fast.stop()
  }

  @Test
  def canNotUseActorsOfDifferentTypesInSameDispatcher(): Unit = {
    val first = actorOf[FirstActor]
    val second = actorOf[SecondActor]

    first.start()
    intercept[IllegalActorStateException] {
      second.start()
    }
  }

  @Test
  def canNotUseActorsOfDifferentSubTypesInSameDispatcher(): Unit = {
    val parent = actorOf[ParentActor]
    val child = actorOf[ChildActor]

    parent.start()
    intercept[IllegalActorStateException] {
      child.start()
    }
  }
}
