package akka.performance.trading.system

import akka.performance.trading.domain._
import akka.actor._

trait MatchingEngine {
  val meId: String
  val orderbooks: List[Orderbook]
  val supportedOrderbookSymbols = orderbooks map (_.symbol)
  protected val orderbooksMap: Map[String, Orderbook] =
    orderbooks.map(o ⇒ (o.symbol, o)).toMap

}

class AkkaMatchingEngine(val meId: String, val orderbooks: List[Orderbook])
  extends Actor with MatchingEngine with ActorLogging {

  var standby: Option[ActorRef] = None

  def receive = {
    case order: Order ⇒
      handleOrder(order)
    case standbyRef: ActorRef ⇒
      standby = Some(standbyRef)
    case unknown ⇒
      log.warning("Received unknown message: " + unknown)
  }

  def handleOrder(order: Order) {
    orderbooksMap.get(order.orderbookSymbol) match {
      case Some(orderbook) ⇒
        standby.foreach(_ forward order)

        orderbook.addOrder(order)
        orderbook.matchOrders()

        done(true, order)

      case None ⇒
        log.warning("Orderbook not handled by this MatchingEngine: " + order.orderbookSymbol)
    }
  }

  def done(status: Boolean, order: Order) {
    if (standby.isEmpty) {
      sender() ! Rsp(order, status)
    }
  }

}
