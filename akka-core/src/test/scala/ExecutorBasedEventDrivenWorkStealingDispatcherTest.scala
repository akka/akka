package se.scalablesolutions.akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import java.util.concurrent.CountDownLatch
import org.scalatest.matchers.MustMatchers
import se.scalablesolutions.akka.dispatch.Dispatchers
import java.util.Random


class ExecutorBasedEventDrivenWorkStealingDispatcherTest extends JUnitSuite with MustMatchers with ActorTestUtil {

  val finishedCounter = new CountDownLatch(101)

  var sCount = 0
  var fCount = 0

  val poolDispatcher = Dispatchers.newExecutorBasedEventDrivenWorkStealingDispatcher("pooled-dispatcher")

  class SlowActor extends Actor {

    messageDispatcher = poolDispatcher
    id = "SlowActor"

    val rnd = new Random
    def receive = {
      case x: Int => {
//        println("s processing " + x)
        Thread.sleep(150) // slow actor
        sCount += 1
        finishedCounter.countDown
      }
    }
  }

  class FastActor extends Actor {

    messageDispatcher = poolDispatcher
    id = "FastActor"

    def receive = {
      case x: Int => {
//        println("f processing " + x)
        fCount += 1
        finishedCounter.countDown
      }
    }
  }

  @Test def test = verify(new TestActor {
    def test = {
      val s = new SlowActor
      val f = new FastActor

      handle(s, f) {
        f ! 0
        for (i <- 1 to 100) {
          // send most work to s
          if (i % 20 == 0)
            f ! i
          else
            s ! i
        }

        finishedCounter.await
        println("sCount = " + sCount)
        println("fCount = " + fCount)
        fCount must be > (sCount)
      }
    }
  })

}

trait ActorTestUtil {
  def handle[T](actors: Actor*)(test: => T): T = {
    for (a <- actors) a.start
    try {
      test
    }
    finally {
      for (a <- actors) a.stop
    }
  }

  def verify(actor: TestActor): Unit = handle(actor) {
    actor.test
  }
}

abstract class TestActor extends Actor with ActorTestUtil {
  def test: Unit

  def receive = {case _ =>}
}