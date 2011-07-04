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
        o ← supportedOrderbooks(me)
      } yield (o.symbol, me))

    matchingEngineForOrderbook = m
    matchingEnginePartitionsIsStale = false
  }

  def supportedOrderbooks(me: ME): List[Orderbook]

}

class AkkaOrderReceiver(val matchingEngines: List[ActorRef], disp: Option[MessageDispatcher])
  extends Actor with OrderReceiver {
  type ME = ActorRef

  for (d ← disp) {
    self.dispatcher = d
  }

  def receive = {
    case order: Order ⇒ placeOrder(order)
    case unknown      ⇒ EventHandler.warning(this, "Received unknown message: " + unknown)
  }

  override def supportedOrderbooks(me: ActorRef): List[Orderbook] = {
    (me ? SupportedOrderbooksReq).get.asInstanceOf[List[Orderbook]]
  }

  def placeOrder(order: Order) = {
    if (matchingEnginePartitionsIsStale) refreshMatchingEnginePartitions()
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
