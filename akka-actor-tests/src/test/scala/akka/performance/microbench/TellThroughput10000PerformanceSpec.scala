package akka.performance.microbench

import akka.performance.workbench.PerformanceSpec
import org.apache.commons.math.stat.descriptive.DescriptiveStatistics
import akka.actor._
import java.util.concurrent.{ ThreadPoolExecutor, CountDownLatch, TimeUnit }
import akka.dispatch._
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import akka.util.Duration
import akka.util.duration._

// -server -Xms512M -Xmx1024M -XX:+UseParallelGC -Dbenchmark=true -Dbenchmark.repeatFactor=500
@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class TellThroughput10000PerformanceSpec extends PerformanceSpec {
  import TellThroughput10000PerformanceSpec._

  /* Experiment with java 7 LinkedTransferQueue
  def linkedTransferQueue(): () ⇒ BlockingQueue[Runnable] =
    () ⇒ new java.util.concurrent.LinkedTransferQueue[Runnable]()

  def createDispatcher(name: String) = {
    val threadPoolConfig = ThreadPoolConfig()
    ThreadPoolConfigDispatcherBuilder(config ⇒
      new Dispatcher(system.dispatcherFactory.prerequisites, name, 5,
        0, UnboundedMailbox(), config, 60000), threadPoolConfig)
      //.withNewThreadPoolWithLinkedBlockingQueueWithUnboundedCapacity
      .copy(config = threadPoolConfig.copy(queueFactory = linkedTransferQueue()))
      .setCorePoolSize(maxClients * 2)
      .build
  }
*/

  def createDispatcher(name: String) = ThreadPoolConfigDispatcherBuilder(config ⇒
    new Dispatcher(system.dispatcherFactory.prerequisites, name, 10000,
      Duration.Zero, UnboundedMailbox(), config, Duration(1, TimeUnit.SECONDS)), ThreadPoolConfig())
    .withNewThreadPoolWithLinkedBlockingQueueWithUnboundedCapacity
    .setCorePoolSize(maxClients * 2)
    .build

  val clientDispatcher = createDispatcher("client-dispatcher")
  //val destinationDispatcher = createDispatcher("destination-dispatcher")

  override def atTermination {
    super.atTermination()
    System.out.println("Cleaning up after TellThroughputPerformanceSpec")
    clientDispatcher.shutdown()
    //destinationDispatcher.shutdown()
  }

  val repeat = 30000L * repeatFactor

  "Tell" must {
    "warmup" in {
      runScenario(4, warmup = true)
    }
    "warmup more" in {
      runScenario(4, warmup = true)
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

    def runScenario(numberOfClients: Int, warmup: Boolean = false) {
      if (acceptClients(numberOfClients)) {

        val latch = new CountDownLatch(numberOfClients)
        val repeatsPerClient = repeat / numberOfClients
        /*
        val destinations = for (i ← 0 until numberOfClients)
          yield system.actorOf(Props(new Destination).withDispatcher(createDispatcher("destination-" + i)))
        val clients = for ((dest, j) ← destinations.zipWithIndex)
          yield system.actorOf(Props(new Client(dest, latch, repeatsPerClient)).withDispatcher(createDispatcher("client-" + j)))
        */
        val destinations = for (i ← 0 until numberOfClients)
          yield system.actorOf(Props(new Destination).withDispatcher(clientDispatcher))
        val clients = for ((dest, j) ← destinations.zipWithIndex)
          yield system.actorOf(Props(new Client(dest, latch, repeatsPerClient)).withDispatcher(clientDispatcher))

        val start = System.nanoTime
        clients.foreach(_ ! Run)
        val ok = latch.await((5000000 + 500 * repeat) * timeDilation, TimeUnit.MICROSECONDS)
        val durationNs = (System.nanoTime - start)

        if (!ok) {
          System.err.println("Destinations: ")
          destinations.foreach {
            case l: LocalActorRef ⇒
              val m = l.underlying.mailbox
              System.err.println("   -" + l + " mbox(" + m.status + ")" + " containing [" + Stream.continually(m.dequeue()).takeWhile(_ != null).mkString(", ") + "] and has systemMsgs: " + m.hasSystemMessages)
          }
          System.err.println("")
          System.err.println("Clients: ")

          clients.foreach {
            case l: LocalActorRef ⇒
              val m = l.underlying.mailbox
              System.err.println("   -" + l + " mbox(" + m.status + ")" + " containing [" + Stream.continually(m.dequeue()).takeWhile(_ != null).mkString(", ") + "] and has systemMsgs: " + m.hasSystemMessages)
          }

          //val e = clientDispatcher.asInstanceOf[Dispatcher].executorService.get().asInstanceOf[ExecutorServiceDelegate].executor.asInstanceOf[ThreadPoolExecutor]
          //val q = e.getQueue
          //System.err.println("Client Dispatcher: " + e.getActiveCount + " " + Stream.continually(q.poll()).takeWhile(_ != null).mkString(", "))
        }

        if (!warmup) {
          ok must be(true)
          logMeasurement(numberOfClients, durationNs, repeat)
        }
        clients.foreach(_ ! PoisonPill)
        destinations.foreach(_ ! PoisonPill)

      }
    }
  }
}

object TellThroughput10000PerformanceSpec {

  case object Run
  case object Msg

  class Destination extends Actor {
    def receive = {
      case Msg ⇒ sender ! Msg
    }
  }

  class Client(
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
        for (i ← 0L until math.min(20000L, repeat)) {
          actor ! Msg
          sent += 1
        }
    }

  }

}
