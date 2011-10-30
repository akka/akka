package akka.performance.trading.oneway

import java.util.concurrent.TimeUnit
import org.junit.Test
import akka.performance.trading.common.AkkaPerformanceTest
import akka.performance.trading.common.Rsp
import akka.performance.trading.domain._
import akka.actor.{ Props, ActorRef }
import akka.AkkaApplication

// -server -Xms512M -Xmx1024M -XX:+UseConcMarkSweepGC -Dbenchmark.useDummyOrderbook=true -Dbenchmark=true -Dbenchmark.minClients=1 -Dbenchmark.maxClients=40 -Dbenchmark.repeatFactor=500
class OneWayPerformanceTest extends AkkaPerformanceTest(AkkaApplication()) {

  val Ok = new Rsp(true)

  override def createTradingSystem: TS = new OneWayTradingSystem(app) {
    override def createMatchingEngine(meId: String, orderbooks: List[Orderbook]) = meDispatcher match {
      case Some(d) ⇒ app.actorOf(Props(new OneWayMatchingEngine(meId, orderbooks) with LatchMessageCountDown).withDispatcher(d))
      case _       ⇒ app.actorOf(new OneWayMatchingEngine(meId, orderbooks) with LatchMessageCountDown)
    }
  }

  override def placeOrder(orderReceiver: ActorRef, order: Order, await: Boolean): Rsp = {
    if (await) {
      val newOrder = LatchOrder(order)
      orderReceiver ! newOrder
      val ok = newOrder.latch.await(10, TimeUnit.SECONDS)
      new Rsp(ok)
    } else {
      orderReceiver ! order
      Ok
    }
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
    order match {
      case x: LatchMessage ⇒ x.latch.countDown
      case _               ⇒
    }
  }
}

