package akka.performance.trading.system

import akka.performance.trading.domain._
import akka.actor._
import akka.dispatch.MessageDispatcher

trait OrderReceiver {
  type ME
  var matchingEngineForOrderbook: Map[String, ME] = Map()

  def refreshMatchingEnginePartitions(routing: MatchingEngineRouting[ME]) {

    val matchingEngines: List[ME] = routing.mapping.keys.toList
    def supportedOrderbooks(me: ME): List[String] = routing.mapping(me)

    val m = Map() ++
      (for {
        me ← matchingEngines
        orderbookSymbol ← supportedOrderbooks(me)
      } yield (orderbookSymbol, me))

    matchingEngineForOrderbook = m
  }

}

class AkkaOrderReceiver extends Actor with OrderReceiver {
  type ME = ActorRef

  def receive = {
    case order: Order ⇒ placeOrder(order)
    case routing @ MatchingEngineRouting(mapping) ⇒
      refreshMatchingEnginePartitions(routing.asInstanceOf[MatchingEngineRouting[ActorRef]])
    case unknown ⇒ app.eventHandler.warning(this, "Received unknown message: " + unknown)
  }

  def placeOrder(order: Order) = {
    val matchingEngine = matchingEngineForOrderbook.get(order.orderbookSymbol)
    matchingEngine match {
      case Some(m) ⇒
        m forward order
      case None ⇒
        app.eventHandler.warning(this, "Unknown orderbook: " + order.orderbookSymbol)
        sender ! Rsp(order, false)
    }
  }
}

case class MatchingEngineRouting[ME](mapping: Map[ME, List[String]])
