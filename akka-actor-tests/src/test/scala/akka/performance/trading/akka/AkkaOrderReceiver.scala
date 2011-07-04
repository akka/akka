package akka.performance.trading.akka

import akka.performance.trading.common.OrderReceiver
import akka.actor._
import akka.dispatch.MessageDispatcher

import akka.performance.trading.domain._

class AkkaOrderReceiver(val matchingEngines: List[ActorRef], disp: Option[MessageDispatcher])
  extends Actor with OrderReceiver {
  type ME = ActorRef

  for (d ← disp) {
    self.dispatcher = d
  }

  def receive = {
    case order: Order ⇒ placeOrder(order)
    case unknown      ⇒ println("Received unknown message: " + unknown)
  }

  override def supportedOrderbooks(me: ActorRef): List[Orderbook] = {
    (me ? SupportedOrderbooksReq).get.asInstanceOf[List[Orderbook]]
  }

  def placeOrder(order: Order) = {
    if (matchingEnginePartitionsIsStale) refreshMatchingEnginePartitions()
    val matchingEngine = matchingEngineForOrderbook.get(order.orderbookSymbol)
    matchingEngine match {
      case Some(m) ⇒
        // println("receiver " + order)
        m.forward(order)
      case None ⇒
        println("Unknown orderbook: " + order.orderbookSymbol)
        self.channel ! new Rsp(false)
    }
  }
}
