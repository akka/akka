package akka.performance.trading.akkabang

import org.junit._
import Assert._

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import akka.performance.trading.akka._
import akka.performance.trading.domain._
import akka.performance.trading.common._

import akka.actor.ActorRef
import akka.actor.Actor.actorOf

class AkkaBangPerformanceTest extends AkkaPerformanceTest {

  override def createTradingSystem: TS = new AkkaBangTradingSystem {
    override def createMatchingEngine(meId: String, orderbooks: List[Orderbook]) =
      actorOf(new AkkaBangMatchingEngine(meId, orderbooks, meDispatcher) with LatchMessageCountDown)
  }

  override def placeOrder(orderReceiver: ActorRef, order: Order): Rsp = {
    val newOrder = LatchOrder(order)
    orderReceiver ! newOrder
    val ok = newOrder.latch.await(10, TimeUnit.SECONDS)
    new Rsp(ok)
  }

  // need this so that junit will detect this as a test case
  @Test
  override def dummy {}

  def createLatchOrder(order: Order) = order match {
    case bid: Bid ⇒ new Bid(order.orderbookSymbol, order.price, order.volume) with LatchMessage { val count = 2 }
    case ask: Ask ⇒ new Ask(order.orderbookSymbol, order.price, order.volume) with LatchMessage { val count = 2 }
  }

}

trait LatchMessageCountDown extends AkkaBangMatchingEngine {

  override def handleOrder(order: Order) {
    super.handleOrder(order)
    order.asInstanceOf[LatchMessage].latch.countDown
  }
}

