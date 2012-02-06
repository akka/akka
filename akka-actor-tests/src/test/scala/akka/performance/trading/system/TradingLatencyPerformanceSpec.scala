package akka.performance.trading.system

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Random
import org.apache.commons.math.stat.descriptive.DescriptiveStatistics
import org.apache.commons.math.stat.descriptive.SynchronizedDescriptiveStatistics
import org.junit.runner.RunWith
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.performance.trading.domain.Ask
import akka.performance.trading.domain.Bid
import akka.performance.trading.domain.Order
import akka.performance.trading.domain.TotalTradeCounter
import akka.performance.workbench.PerformanceSpec
import akka.performance.trading.domain.Orderbook
import akka.performance.trading.domain.TotalTradeCounterExtension

// -server -Xms512M -Xmx1024M -XX:+UseConcMarkSweepGC -Dbenchmark=true -Dbenchmark.repeatFactor=500 -Dbenchmark.useDummyOrderbook=true
@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class TradingLatencyPerformanceSpec extends PerformanceSpec {

  var tradingSystem: AkkaTradingSystem = _

  var stat: DescriptiveStatistics = _
  val random: Random = new Random(0)

  def totalTradeCounter: TotalTradeCounter = TotalTradeCounterExtension(system)

  override def beforeEach() {
    super.beforeEach()
    stat = new SynchronizedDescriptiveStatistics
    tradingSystem = new AkkaTradingSystem(system)
    tradingSystem.start()
    totalTradeCounter.reset()
    stat = new SynchronizedDescriptiveStatistics
  }

  override def afterEach() {
    super.afterEach()
    tradingSystem.shutdown()
    stat = null
  }

  getClass.getSimpleName must {
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

  }

  def runScenario(numberOfClients: Int, warmup: Boolean = false) {
    if (acceptClients(numberOfClients)) {

      val repeat = 4L * repeatFactor

      val prefixes = "A" :: "B" :: "C" :: "D" :: Nil
      val askOrders = for {
        s ← prefixes
        i ← 1 to 3
      } yield Ask(s + i, 100 - i, 1000)
      val bidOrders = for {
        s ← prefixes
        i ← 1 to 3
      } yield Bid(s + i, 100 - i, 1000)
      val orders = askOrders.zip(bidOrders).map(x ⇒ Seq(x._1, x._2)).flatten

      val latencyDispatcher = "benchmark.trading-dispatcher"

      val ordersPerClient = repeat * orders.size / numberOfClients
      val totalNumberOfOrders = ordersPerClient * numberOfClients
      val latch = new CountDownLatch(numberOfClients)
      val receivers = tradingSystem.orderReceivers.toIndexedSeq
      val start = System.nanoTime
      val clients = (for (i ← 0 until numberOfClients) yield {
        val receiver = receivers(i % receivers.size)
        val props = Props(new Client(receiver, orders, latch, ordersPerClient, clientDelay.toMicros.toInt)).withDispatcher(latencyDispatcher)
        system.actorOf(props)
      })

      clients.foreach(_ ! "run")
      val ok = latch.await(maxRunDuration.toMillis, TimeUnit.MILLISECONDS)
      val durationNs = (System.nanoTime - start)

      if (!warmup) {
        ok must be(true)
        if (!Orderbook.useDummyOrderbook) {
          totalTradeCounter.count must be(totalNumberOfOrders / 2)
        }
        logMeasurement(numberOfClients, durationNs, stat)
      }
      clients.foreach(system.stop(_))
    }
  }

  class Client(
    orderReceiver: ActorRef,
    orders: List[Order],
    latch: CountDownLatch,
    repeat: Long,
    delayMicros: Int = 0) extends Actor {

    var orderIterator = orders.toIterator
    def nextOrder(): Order = {
      if (!orderIterator.hasNext) {
        orderIterator = orders.toIterator
      }
      orderIterator.next()
    }

    var sent = 0L
    var received = 0L

    def receive = {
      case Rsp(order, status) ⇒
        if (!status) {
          log.error("Invalid rsp")
        }
        val duration = System.nanoTime - order.nanoTime
        stat.addValue(duration)
        received += 1
        if (sent < repeat) {
          PerformanceSpec.shortDelay(delayMicros, received)
          placeOrder()
          sent += 1
        } else if (received >= repeat) {
          latch.countDown()
        }

      case "run" ⇒
        // random initial delay to spread requests
        val initialDelay = random.nextInt(20)
        Thread.sleep(initialDelay)
        placeOrder()
        sent += 1
    }

    def placeOrder() {
      orderReceiver ! nextOrder().withNanoTime
    }

  }

}

