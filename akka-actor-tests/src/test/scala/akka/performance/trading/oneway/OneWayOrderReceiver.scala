package akka.performance.trading.oneway

import akka.actor._
import akka.dispatch.MessageDispatcher
import akka.event.EventHandler
import akka.performance.trading.domain._
import akka.performance.trading.common.AkkaOrderReceiver

class OneWayOrderReceiver(matchingEngineRouting: Map[ActorRef, List[String]], disp: Option[MessageDispatcher])
  extends AkkaOrderReceiver(matchingEngineRouting, disp) {

  override def placeOrder(order: Order) = {
    val matchingEngine = matchingEngineForOrderbook.get(order.orderbookSymbol)
    matchingEngine match {
      case Some(m) ⇒
        m ! order
      case None ⇒
        EventHandler.warning(this, "Unknown orderbook: " + order.orderbookSymbol)
    }
  }
}
