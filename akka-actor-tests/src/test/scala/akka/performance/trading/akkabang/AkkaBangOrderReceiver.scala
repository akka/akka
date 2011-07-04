package akka.performance.trading.akkabang

import akka.actor._
import akka.dispatch.MessageDispatcher

import akka.performance.trading.akka._
import akka.performance.trading.domain._

class AkkaBangOrderReceiver(matchingEngines: List[ActorRef], disp: Option[MessageDispatcher])
  extends AkkaOrderReceiver(matchingEngines, disp) {

  override def placeOrder(order: Order) = {
    if (matchingEnginePartitionsIsStale) refreshMatchingEnginePartitions()
    val matchingEngine = matchingEngineForOrderbook.get(order.orderbookSymbol)
    matchingEngine match {
      case Some(m) ⇒
        // println("receiver " + order)
        m ! order
      case None ⇒
        println("Unknown orderbook: " + order.orderbookSymbol)
    }
  }
}
