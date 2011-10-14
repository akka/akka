package akka.performance.trading.oneway

import akka.actor._
import akka.dispatch.MessageDispatcher
import akka.performance.trading.domain._
import akka.performance.trading.common.AkkaOrderReceiver

class OneWayOrderReceiver extends AkkaOrderReceiver {

  override def placeOrder(order: Order) = {
    val matchingEngine = matchingEngineForOrderbook.get(order.orderbookSymbol)
    matchingEngine match {
      case Some(m) ⇒
        m ! order
      case None ⇒
        app.eventHandler.warning(this, "Unknown orderbook: " + order.orderbookSymbol)
    }
  }
}
