package akka.performance.trading.common

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import akka.performance.trading.domain._
import akka.performance.trading.common._
import akka.actor.{ Props, ActorRef, Actor, PoisonPill }
import akka.AkkaApplication

abstract class AkkaPerformanceTest(val app: AkkaApplication) extends BenchmarkScenarios {

  type TS = AkkaTradingSystem

  val clientDispatcher = app.dispatcherFactory.newDispatcher("client-dispatcher")
    .withNewThreadPoolWithLinkedBlockingQueueWithUnboundedCapacity
    .setCorePoolSize(maxClients)
    .setMaxPoolSize(maxClients)
    .build

  override def createTradingSystem: TS = new AkkaTradingSystem(app)

  /**
   * Implemented in subclass
   */
  def placeOrder(orderReceiver: ActorRef, order: Order, await: Boolean): Rsp

  override def runScenario(scenario: String, orders: List[Order], repeat: Int, numberOfClients: Int, delayMs: Int) = {
    val totalNumberOfRequests = orders.size * repeat
    val repeatsPerClient = repeat / numberOfClients
    val oddRepeats = repeat - (repeatsPerClient * numberOfClients)
    val latch = new CountDownLatch(numberOfClients)
    val receivers = tradingSystem.orderReceivers.toIndexedSeq
    val start = System.nanoTime
    val clients = (for (i ← 0 until numberOfClients) yield {
      val receiver = receivers(i % receivers.size)
      Props(new Client(receiver, orders, latch, repeatsPerClient + (if (i < oddRepeats) 1 else 0), sampling, delayMs)).withDispatcher(clientDispatcher)
    }).toList.map(app.actorOf(_))

    clients.foreach(_ ! "run")
    val ok = latch.await((5000 + (2 + delayMs) * totalNumberOfRequests) * timeDilation, TimeUnit.MILLISECONDS)
    val durationNs = (System.nanoTime - start)

    assert(ok)
    assert((orders.size / 2) * repeat == TotalTradeCounter.counter.get)
    logMeasurement(scenario, numberOfClients, durationNs)
    clients.foreach(_ ! PoisonPill)
  }

  class Client(
    orderReceiver: ActorRef,
    orders: List[Order],
    latch: CountDownLatch,
    repeat: Int,
    sampling: Int,
    delayMs: Int = 0) extends Actor {

    def receive = {
      case "run" ⇒
        var n = 0
        for (r ← 1 to repeat; o ← orders) {
          n += 1

          val rsp =
            if (measureLatency(n)) {
              val t0 = System.nanoTime
              val rsp = placeOrder(orderReceiver, o, await = true)
              val duration = System.nanoTime - t0
              stat.addValue(duration)
              rsp
            } else {
              val await = measureLatency(n + 1) || (r == repeat)
              placeOrder(orderReceiver, o, await)
            }
          if (!rsp.status) {
            app.eventHandler.error(this, "Invalid rsp")
          }
          delay(delayMs)
        }
        latch.countDown()
    }

    def measureLatency(n: Int) = (n % sampling == 0)
  }

}

