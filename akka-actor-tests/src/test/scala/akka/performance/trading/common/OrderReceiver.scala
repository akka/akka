package akka.performance.trading.common

import akka.performance.trading.domain._
import akka.actor._
import akka.dispatch.MessageDispatcher
import akka.event.EventHandler

trait OrderReceiver {
  type ME
  val matchingEngines: List[ME]
  var matchingEnginePartitionsIsStale = true
  var matchingEngineForOrderbook: Map[String, ME] = Map()

  def refreshMatchingEnginePartitions() {
    val m = Map() ++
      (for {
        me ← matchingEngines
        orderbookSymbol ← supportedOrderbooks(me)
      } yield (orderbookSymbol, me))

    matchingEngineForOrderbook = m
    matchingEnginePartitionsIsStale = false
  }

  def supportedOrderbooks(me: ME): List[String]

}

class AkkaOrderReceiver(matchingEngineRouting: Map[ActorRef, List[String]], disp: Option[MessageDispatcher])
  extends Actor with OrderReceiver {
  type ME = ActorRef

  for (d ← disp) {
    self.dispatcher = d
  }

  override val matchingEngines: List[ActorRef] = matchingEngineRouting.keys.toList

  override def preStart() {
    refreshMatchingEnginePartitions()
  }

  def receive = {
    case order: Order ⇒ placeOrder(order)
    case unknown      ⇒ EventHandler.warning(this, "Received unknown message: " + unknown)
  }

  override def supportedOrderbooks(me: ActorRef): List[String] = {
    matchingEngineRouting(me)
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
