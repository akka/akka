package se.scalablesolutions.akka.actor

import org.scalatest.matchers.MustMatchers
import org.scalatest.junit.JUnitSuite

import org.junit.Test

import se.scalablesolutions.akka.dispatch.Dispatchers
import Actor._

import java.util.concurrent.{TimeUnit, CountDownLatch}

/**
 * @author Jan Van Besien
 */
class ExecutorBasedEventDrivenWorkStealingDispatcherSpec extends JUnitSuite with MustMatchers {
  val poolDispatcher = Dispatchers.newExecutorBasedEventDrivenWorkStealingDispatcher("pooled-dispatcher")

  class DelayableActor(name: String, delay: Int, finishedCounter: CountDownLatch) extends Actor {
    messageDispatcher = poolDispatcher
    var invocationCount = 0
    id = name

    def receive = {
      case x: Int => {
        Thread.sleep(delay)
        invocationCount += 1
        finishedCounter.countDown
      }
    }
  }

  @Test def fastActorShouldStealWorkFromSlowActor = {
    val finishedCounter = new CountDownLatch(110)

    val slow = newActor(() => new DelayableActor("slow", 50, finishedCounter)).start
    val fast = newActor(() => new DelayableActor("fast", 10, finishedCounter)).start

    for (i <- 1 to 100) {
      // send most work to slow actor
      if (i % 20 == 0)
        fast ! i
      else
        slow ! i
    }

    // now send some messages to actors to keep the dispatcher dispatching messages
    for (i <- 1 to 10) {
      Thread.sleep(150)
      if (i % 2 == 0)
        fast ! i
      else
        slow ! i
    }

    finishedCounter.await(5, TimeUnit.SECONDS)
    fast.actor.asInstanceOf[DelayableActor].invocationCount must be > (slow.actor.asInstanceOf[DelayableActor].invocationCount)
    slow.stop
    fast.stop
  }
  
  @Test def canNotUseActorsOfDifferentTypesInSameDispatcher = {
    val first = newActor[FirstActor]
    val second = newActor[SecondActor]

    first.start
    intercept[IllegalStateException] {
      second.start
    }
  }

  class FirstActor extends Actor {
    messageDispatcher = poolDispatcher
    def receive = {case _ => {}}
  }

  class SecondActor extends Actor {
    messageDispatcher = poolDispatcher
    def receive = {case _ => {}}
  }

  @Test def canNotUseActorsOfDifferentSubTypesInSameDispatcher:Unit = {
    val parent = new ParentActor
    val child = new ChildActor

    parent.start
    intercept[IllegalStateException] {
      child.start
    }
  }

  class ParentActor extends Actor {
    messageDispatcher = poolDispatcher
    def receive = {case _ => {}}
  }

  class ChildActor extends ParentActor {
  }

}
