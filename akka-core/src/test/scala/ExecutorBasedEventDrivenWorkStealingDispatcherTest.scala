package se.scalablesolutions.akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import java.util.concurrent.CountDownLatch
import org.scalatest.matchers.MustMatchers
import se.scalablesolutions.akka.dispatch.Dispatchers

/**
 * @author Jan Van Besien
 */
class ExecutorBasedEventDrivenWorkStealingDispatcherTest extends JUnitSuite with MustMatchers with ActorTestUtil {
  val poolDispatcher = Dispatchers.newExecutorBasedEventDrivenWorkStealingDispatcher("pooled-dispatcher")

  class DelayableActor(id: String, delay: Int, finishedCounter: CountDownLatch) extends Actor {
    messageDispatcher = poolDispatcher
    var invocationCount = 0

    def receive = {
      case x: Int => {
        Thread.sleep(delay)
        invocationCount += 1
        finishedCounter.countDown
        println(id + " processed " + x)
      }
    }
  }

  @Test def fastActorShouldStealWorkFromSlowActor = verify(new TestActor {
    def test = {
      val finishedCounter = new CountDownLatch(100)

      val slow = new DelayableActor("slow", 50, finishedCounter)
      val fast = new DelayableActor("fast", 10, finishedCounter)

      handle(slow, fast) {
        for (i <- 1 to 100) {
          // send most work to slow actor
          if (i % 20 == 0)
            fast ! i
          else
            slow ! i
        }

        finishedCounter.await
        fast.invocationCount must be > (slow.invocationCount)
      }
    }
  })

//  @Test def canNotRegisterTwoDifferentActors = {
//    new Actor() {
//      override var messageDispatcher = poolDispatcher
//    }
//
//    intercept(classOf[NullPointerException]) {
//      new Actor() {
//        override var messageDispatcher = poolDispatcher
//      }
//    }
//  }

}
