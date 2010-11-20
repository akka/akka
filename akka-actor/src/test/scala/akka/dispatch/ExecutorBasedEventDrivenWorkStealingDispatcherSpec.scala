package akka.actor.dispatch

import org.scalatest.matchers.MustMatchers
import org.scalatest.junit.JUnitSuite

import org.junit.Test

import java.util.concurrent.{TimeUnit, CountDownLatch}
import akka.actor.{IllegalActorStateException, Actor}
import Actor._
import akka.dispatch.{MessageQueue, Dispatchers}

object ExecutorBasedEventDrivenWorkStealingDispatcherSpec {
  val delayableActorDispatcher = Dispatchers.newExecutorBasedEventDrivenWorkStealingDispatcher("pooled-dispatcher").build
  val sharedActorDispatcher = Dispatchers.newExecutorBasedEventDrivenWorkStealingDispatcher("pooled-dispatcher").build
  val parentActorDispatcher = Dispatchers.newExecutorBasedEventDrivenWorkStealingDispatcher("pooled-dispatcher").build

  class DelayableActor(name: String, delay: Int, finishedCounter: CountDownLatch) extends Actor {
    self.dispatcher = delayableActorDispatcher
    @volatile var invocationCount = 0
    self.id = name

    def receive = {
      case x: Int => {
        Thread.sleep(delay)
        invocationCount += 1
        finishedCounter.countDown
      }
    }
  }

  class FirstActor extends Actor {
    self.dispatcher = sharedActorDispatcher
    def receive = {case _ => {}}
  }

  class SecondActor extends Actor {
    self.dispatcher = sharedActorDispatcher
    def receive = {case _ => {}}
  }

  class ParentActor extends Actor {
    self.dispatcher = parentActorDispatcher
    def receive = {case _ => {}}
  }

  class ChildActor extends ParentActor {
  }
}

/**
 * @author Jan Van Besien
 */
class ExecutorBasedEventDrivenWorkStealingDispatcherSpec extends JUnitSuite with MustMatchers {
  import ExecutorBasedEventDrivenWorkStealingDispatcherSpec._

  @Test def fastActorShouldStealWorkFromSlowActor  {
    val finishedCounter = new CountDownLatch(110)

    val slow = actorOf(new DelayableActor("slow", 50, finishedCounter)).start
    val fast = actorOf(new DelayableActor("fast", 10, finishedCounter)).start

    var sentToFast = 0

    for (i <- 1 to 100) {
      // send most work to slow actor
      if (i % 20 == 0) {
        fast ! i
        sentToFast += 1
      }
      else
        slow ! i
    }

    // now send some messages to actors to keep the dispatcher dispatching messages
    for (i <- 1 to 10) {
      Thread.sleep(150)
      if (i % 2 == 0) {
        fast ! i
        sentToFast += 1
      }
      else
        slow ! i
    }

    finishedCounter.await(5, TimeUnit.SECONDS)
    fast.mailbox.asInstanceOf[MessageQueue].isEmpty must be(true)
    slow.mailbox.asInstanceOf[MessageQueue].isEmpty must be(true)
    fast.actor.asInstanceOf[DelayableActor].invocationCount must be > sentToFast
    fast.actor.asInstanceOf[DelayableActor].invocationCount must be >
    (slow.actor.asInstanceOf[DelayableActor].invocationCount)
    slow.stop
    fast.stop
  }

  @Test def canNotUseActorsOfDifferentTypesInSameDispatcher(): Unit = {
    val first = actorOf[FirstActor]
    val second = actorOf[SecondActor]

    first.start
    intercept[IllegalActorStateException] {
      second.start
    }
  }

  @Test def canNotUseActorsOfDifferentSubTypesInSameDispatcher(): Unit = {
    val parent = actorOf[ParentActor]
    val child = actorOf[ChildActor]

    parent.start
    intercept[IllegalActorStateException] {
      child.start
    }
  }
}
