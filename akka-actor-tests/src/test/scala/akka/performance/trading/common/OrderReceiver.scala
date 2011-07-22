package akka.performance.trading.common

import akka.performance.trading.domain._
import akka.actor._
import akka.dispatch.MessageDispatcher
import akka.event.EventHandler

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

class AkkaOrderReceiver(disp: Option[MessageDispatcher])
  extends Actor with OrderReceiver {
  type ME = ActorRef

  for (d ← disp) {
    self.dispatcher = d
  }

  def receive = {
    case routing@MatchingEngineRouting(mapping) ⇒
      refreshMatchingEnginePartitions(routing.asInstanceOf[MatchingEngineRouting[ActorRef]])
    case order: Order ⇒ placeOrder(order)
    case unknown      ⇒ EventHandler.warning(this, "Received unknown message: " + unknown)
  }

  def placeOrder(order: Order) = {
    val matchingEngine = matchingEngineForOrderbook.get(order.orderbookSymbol)
    matchingEngine match {
      case Some(m) ⇒
        m.forward(order)
      case None ⇒
        EventHandler.warning(this, "Unknown orderbook: " + order.orderbookSymbol)
        self.channel ! new Rsp(false)
    }
  }
}

case class MatchingEngineRouting[ME](mapping: Map[ME, List[String]])
