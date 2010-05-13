package se.scalablesolutions.akka.actor

import org.scalatest.matchers.MustMatchers
import org.scalatest.junit.JUnitSuite

import org.junit.Test

import se.scalablesolutions.akka.dispatch.Dispatchers
import Actor._

import java.util.concurrent.{TimeUnit, CountDownLatch}

object ExecutorBasedEventDrivenWorkStealingDispatcherSpec { 
  val delayableActorDispatcher = Dispatchers.newExecutorBasedEventDrivenWorkStealingDispatcher("pooled-dispatcher")
  val sharedActorDispatcher = Dispatchers.newExecutorBasedEventDrivenWorkStealingDispatcher("pooled-dispatcher")
  val parentActorDispatcher = Dispatchers.newExecutorBasedEventDrivenWorkStealingDispatcher("pooled-dispatcher")

  class DelayableActor(name: String, delay: Int, finishedCounter: CountDownLatch) extends Actor {
    dispatcher = delayableActorDispatcher
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
  
  class FirstActor extends Actor {
    dispatcher = sharedActorDispatcher
    def receive = {case _ => {}}
  }

  class SecondActor extends Actor {
    dispatcher = sharedActorDispatcher
    def receive = {case _ => {}}
  }

  class ParentActor extends Actor {
    dispatcher = parentActorDispatcher
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
    fast.actor.asInstanceOf[DelayableActor].invocationCount must be > 
    (slow.actor.asInstanceOf[DelayableActor].invocationCount)
    slow.stop
    fast.stop
  }
  
  @Test def canNotUseActorsOfDifferentTypesInSameDispatcher: Unit = {
    val first = newActor[FirstActor]
    val second = newActor[SecondActor]

    first.start
    intercept[IllegalStateException] {
      second.start
    }
  }

  @Test def canNotUseActorsOfDifferentSubTypesInSameDispatcher: Unit = {
    val parent = newActor[ParentActor]
    val child = newActor[ChildActor]

    parent.start
    intercept[IllegalStateException] {
      child.start
    }
  }
}
