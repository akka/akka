package se.scalablesolutions.akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import java.util.concurrent.CountDownLatch
import org.scalatest.matchers.MustMatchers
import se.scalablesolutions.akka.dispatch.Dispatchers
import java.util.Random


class ExecutorBasedEventDrivenWorkStealingDispatcherTest extends JUnitSuite with MustMatchers with ActorTestUtil {
  val poolDispatcher = Dispatchers.newExecutorBasedEventDrivenWorkStealingDispatcher("pooled-dispatcher")

  class SlowActor(finishedCounter:CountDownLatch) extends Actor {
    messageDispatcher = poolDispatcher
    id = "SlowActor"
    var invocationCount = 0

    def receive = {
      case x: Int => {
        Thread.sleep(50) // slow actor
        invocationCount += 1
        finishedCounter.countDown
      }
    }
  }

  class FastActor(finishedCounter:CountDownLatch) extends Actor {
    messageDispatcher = poolDispatcher
    id = "FastActor"
    var invocationCount = 0

    def receive = {
      case x: Int => {
        invocationCount += 1
        finishedCounter.countDown
      }
    }
  }

  @Test def fastActorShouldStealWorkFromSlowActor = verify(new TestActor {
    def test = {
      val finishedCounter = new CountDownLatch(100)

      val s = new SlowActor(finishedCounter)
      val f = new FastActor(finishedCounter)

      handle(s, f) {
        for (i <- 1 to 100) {
          // send most work to s
          if (i % 20 == 0)
            f ! i
          else
            s ! i
        }

        finishedCounter.await
        f.invocationCount must be > (s.invocationCount)
      }
    }
  })

}
