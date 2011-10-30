package akka.performance.microbench

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import org.apache.commons.math.stat.descriptive.DescriptiveStatistics
import org.junit.runner.RunWith

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.PoisonPill
import akka.actor.Props

// -server -Xms512M -Xmx1024M -XX:+UseConcMarkSweepGC -Dbenchmark=true -Dbenchmark.repeatFactor=500
@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class TellPerformanceSpec extends PerformanceSpec {
  import TellPerformanceSpec._

  val clientDispatcher = app.dispatcherFactory.newDispatcher("client-dispatcher")
    .withNewThreadPoolWithLinkedBlockingQueueWithUnboundedCapacity
    .setCorePoolSize(maxClients)
    .build

  val repeat = repeatFactor * 30000

  "Tell" must {
    "warmup" in {
      runScenario(2, warmup = true)
    }
    "perform with load 1" in {
      runScenario(1)
    }
    "perform with load 2" in {
      runScenario(2)
    }
    "perform with load 4" in {
      runScenario(4)
    }
    "perform with load 6" in {
      runScenario(6)
    }
    "perform with load 8" in {
      runScenario(8)
    }

    def runScenario(numberOfClients: Int, warmup: Boolean = false) {
      if (numberOfClients <= maxClients) {

        val latch = new CountDownLatch(numberOfClients)
        val repeatsPerClient = repeat / numberOfClients
        val clients = (for (i ← 0 until numberOfClients) yield {
          val c = app.actorOf[Destination]
          val b = app.actorOf(new Waypoint(c))
          val a = app.actorOf(new Waypoint(b))
          Props(new Client(a, latch, repeatsPerClient, sampling, stat)).withDispatcher(clientDispatcher)
        }).toList.map(app.actorOf(_))

        val start = System.nanoTime
        clients.foreach(_ ! Run)
        latch.await(30, TimeUnit.SECONDS) must be(true)
        val durationNs = (System.nanoTime - start)

        if (!warmup) {
          logMeasurement("one-way tell", numberOfClients, durationNs)
        }
        clients.foreach(_ ! PoisonPill)

      }
    }
  }
}

object TellPerformanceSpec {

  case object Run
  case class Msg(latch: Option[CountDownLatch])

  class Waypoint(next: ActorRef) extends Actor {
    def receive = {
      case msg: Msg ⇒ next ! msg
    }
  }

  class Destination extends Actor {
    def receive = {
      case Msg(latch) ⇒ latch.foreach(_.countDown())
    }
  }

  class Client(
    actor: ActorRef,
    latch: CountDownLatch,
    repeat: Int,
    sampling: Int,
    stat: DescriptiveStatistics) extends Actor {

    def receive = {
      case Run ⇒
        val msgWithoutLatch = Msg(None)
        for (n ← 1 to repeat) {
          if (measureLatency(n)) {
            val t0 = System.nanoTime
            tellAndAwait()
            val duration = System.nanoTime - t0
            stat.addValue(duration)
          } else if (measureLatency(n + 1) || n == repeat) {
            tellAndAwait()
          } else {
            actor ! msgWithoutLatch
          }
        }
        latch.countDown()
    }

    def tellAndAwait() {
      val msgLatch = new CountDownLatch(1)
      actor ! Msg(Some(msgLatch))
      val ok = msgLatch.await(10, TimeUnit.SECONDS)
      if (!ok) app.eventHandler.error(this, "Too long delay")
    }

    def measureLatency(n: Int) = (n % sampling == 0)
  }

}