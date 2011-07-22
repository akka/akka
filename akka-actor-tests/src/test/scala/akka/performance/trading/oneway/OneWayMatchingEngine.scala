package akka.performance.trading.oneway

import akka.actor._
import akka.dispatch.MessageDispatcher
import akka.event.EventHandler
import akka.performance.trading.domain.Order
import akka.performance.trading.domain.Orderbook
import akka.performance.trading.common.AkkaMatchingEngine

class OneWayMatchingEngine(meId: String, orderbooks: List[Orderbook], disp: Option[MessageDispatcher])
  extends AkkaMatchingEngine(meId, orderbooks, disp) {

  override def handleOrder(order: Order) {
    orderbooksMap.get(order.orderbookSymbol) match {
      case Some(orderbook) ⇒
        standby.foreach(_ ! order)

        orderbook.addOrder(order)
        orderbook.matchOrders()

      case None ⇒
        EventHandler.warning(this, "Orderbook not handled by this MatchingEngine: " + order.orderbookSymbol)
    }
  }

}
