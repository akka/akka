package akka.performance.trading.akkabang

import akka.actor._
import akka.dispatch.MessageDispatcher

import akka.performance.trading.akka._
import akka.performance.trading.domain.Order
import akka.performance.trading.domain.Orderbook

class AkkaBangMatchingEngine(meId: String, orderbooks: List[Orderbook], disp: Option[MessageDispatcher])
  extends AkkaMatchingEngine(meId, orderbooks, disp) {

  override def handleOrder(order: Order) {
    orderbooksMap.get(order.orderbookSymbol) match {
      case Some(orderbook) ⇒
        // println(meId + " " + order)

        standby.foreach(_ ! order)

        txLog.storeTx(order)
        orderbook.addOrder(order)
        orderbook.matchOrders()

      case None ⇒
        println("Orderbook not handled by this MatchingEngine: " + order.orderbookSymbol)
    }
  }

}
