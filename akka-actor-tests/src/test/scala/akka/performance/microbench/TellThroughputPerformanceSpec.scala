package akka.performance.microbench

import language.postfixOps
import akka.performance.workbench.PerformanceSpec
import akka.actor._
import java.util.concurrent.{ ThreadPoolExecutor, CountDownLatch, TimeUnit }
import akka.dispatch._
import scala.concurrent.duration._

// -server -Xms512M -Xmx1024M -XX:+UseParallelGC -Dbenchmark=true -Dbenchmark.repeatFactor=500
@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class TellThroughputPerformanceSpec extends PerformanceSpec {
  import TellThroughputPerformanceSpec._

  val defaultRepeat = 30000L * repeatFactor

  def getClient(num: Int, actor: ActorRef, latch: CountDownLatch, repeat: Long): Props =
    Props(if (num % 2 == 0) classOf[Client1] else classOf[Client2], actor, latch, repeat)

  def getDestination(num: Int): Props =
    Props(if (num % 3 == 0) classOf[Destination1] else classOf[Destination2])

  override def expectedTestDuration: FiniteDuration = 3 minutes

  "Tell" must {
    "warmup" in {
      runScenario(8, warmup = true, repeat = defaultRepeat / 4)
    }
    "warmup more" in {
      runScenario(1, warmup = true, repeat = defaultRepeat / 8)
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
    "perform with load 10" in {
      runScenario(10)
    }
    "perform with load 12" in {
      runScenario(12)
    }
    "perform with load 14" in {
      runScenario(14)
    }
    "perform with load 16" in {
      runScenario(16)
    }
    "perform with load 18" in {
      runScenario(18)
    }
    "perform with load 20" in {
      runScenario(20)
    }
    "perform with load 22" in {
      runScenario(22)
    }
    "perform with load 24" in {
      runScenario(24)
    }
    "perform with load 26" in {
      runScenario(26)
    }
    "perform with load 28" in {
      runScenario(28)
    }
    "perform with load 30" in {
      runScenario(30)
    }
    "perform with load 32" in {
      runScenario(32)
    }
    "perform with load 34" in {
      runScenario(34)
    }
    "perform with load 36" in {
      runScenario(36)
    }
    "perform with load 38" in {
      runScenario(38)
    }
    "perform with load 40" in {
      runScenario(40)
    }
    "perform with load 42" in {
      runScenario(42)
    }
    "perform with load 44" in {
      runScenario(44)
    }
    "perform with load 46" in {
      runScenario(46)
    }
    "perform with load 48" in {
      runScenario(48)
    }

    def runScenario(numberOfClients: Int, warmup: Boolean = false, repeat: Long = defaultRepeat) {
      if (acceptClients(numberOfClients) || warmup) {

        val throughputDispatcher = "benchmark.throughput-dispatcher"

        val latch = new CountDownLatch(numberOfClients)
        val repeatsPerClient = repeat / numberOfClients
        val (destinations, clients) = (for {
          i ← 0 until numberOfClients
          dest = system.actorOf(getDestination(i).withDispatcher(throughputDispatcher))
          client = system.actorOf(getClient(i, dest, latch, repeatsPerClient).withDispatcher(throughputDispatcher))
        } yield (dest, client)).unzip

        val start = System.nanoTime
        clients.foreach(_ ! Run)
        val ok = latch.await(maxRunDuration.toMillis, TimeUnit.MILLISECONDS)
        val durationNs = (System.nanoTime - start)

        if (!warmup) {
          ok should be(true)
          logMeasurement(numberOfClients, durationNs, repeat)
        }
        clients.foreach(system.stop(_))
        destinations.foreach(system.stop(_))

      }
    }
  }
}

object TellThroughputPerformanceSpec {

  case object Run
  case object Msg

  class Destination1 extends Actor {
    def receive = {
      case Msg ⇒ sender() ! Msg
    }
  }

  class Destination2 extends Actor {
    def receive = {
      case Msg ⇒ sender() ! Msg
    }
  }

  class Client1(
    actor: ActorRef,
    latch: CountDownLatch,
    repeat: Long) extends Actor {

    var sent = 0L
    var received = 0L

    def receive = {
      case Msg ⇒
        received += 1
        if (sent < repeat) {
          actor ! Msg
          sent += 1
        } else if (received >= repeat) {
          latch.countDown()
        }
      case Run ⇒
        for (i ← 0L until math.min(1000L, repeat)) {
          actor ! Msg
          sent += 1
        }
    }
  }

  class Client2(
    actor: ActorRef,
    latch: CountDownLatch,
    repeat: Long) extends Actor {

    var sent = 0L
    var received = 0L

    def receive = {
      case Msg ⇒
        received += 1
        if (sent < repeat) {
          actor ! Msg
          sent += 1
        } else if (received >= repeat) {
          latch.countDown()
        }
      case Run ⇒
        for (i ← 0L until math.min(1000L, repeat)) {
          actor ! Msg
          sent += 1
        }
    }
  }

}
