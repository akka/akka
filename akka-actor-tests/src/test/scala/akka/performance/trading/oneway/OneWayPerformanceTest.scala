package akka.performance.trading.oneway

import java.util.concurrent.TimeUnit

import org.junit.Test

import akka.actor.Actor.actorOf
import akka.actor.ActorRef
import akka.performance.trading.common.AkkaPerformanceTest
import akka.performance.trading.common.Rsp
import akka.performance.trading.domain._

class OneWayPerformanceTest extends AkkaPerformanceTest {

  override def createTradingSystem: TS = new OneWayTradingSystem {
    override def createMatchingEngine(meId: String, orderbooks: List[Orderbook]) =
      actorOf(new OneWayMatchingEngine(meId, orderbooks, meDispatcher) with LatchMessageCountDown)
  }

  override def placeOrder(orderReceiver: ActorRef, order: Order): Rsp = {
    val newOrder = LatchOrder(order)
    orderReceiver ! newOrder
    val ok = newOrder.latch.await(10, TimeUnit.SECONDS)
    new Rsp(ok)
  }

  // need this so that junit will detect this as a test case
  @Test
  def dummy {}

  override def compareResultWith = Some("RspPerformanceTest")

  def createLatchOrder(order: Order) = order match {
    case bid: Bid ⇒ new Bid(order.orderbookSymbol, order.price, order.volume) with LatchMessage { val count = 2 }
    case ask: Ask ⇒ new Ask(order.orderbookSymbol, order.price, order.volume) with LatchMessage { val count = 2 }
  }

}

trait LatchMessageCountDown extends OneWayMatchingEngine {

  override def handleOrder(order: Order) {
    super.handleOrder(order)
    order.asInstanceOf[LatchMessage].latch.countDown
  }
}

