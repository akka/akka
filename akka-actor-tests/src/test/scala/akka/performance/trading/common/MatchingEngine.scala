package akka.performance.trading.common

import akka.performance.trading.domain._
import akka.actor._
import akka.dispatch.Future
import akka.dispatch.FutureTimeoutException
import akka.dispatch.MessageDispatcher

trait MatchingEngine {
  val meId: String
  val orderbooks: List[Orderbook]
  val supportedOrderbookSymbols = orderbooks map (_.symbol)
  protected val orderbooksMap: Map[String, Orderbook] =
    Map() ++ (orderbooks map (o ⇒ (o.symbol, o)))

}

class AkkaMatchingEngine(val meId: String, val orderbooks: List[Orderbook])
  extends Actor with MatchingEngine {

  var standby: Option[ActorRef] = None

  def receive = {
    case standbyRef: ActorRef ⇒
      standby = Some(standbyRef)
    case order: Order ⇒
      handleOrder(order)
    case unknown ⇒
      app.eventHandler.warning(this, "Received unknown message: " + unknown)
  }

  def handleOrder(order: Order) {
    orderbooksMap.get(order.orderbookSymbol) match {
      case Some(orderbook) ⇒
        val pendingStandbyReply: Option[Future[_]] =
          for (s ← standby) yield { s ? order }

        orderbook.addOrder(order)
        orderbook.matchOrders()
        // wait for standby reply
        pendingStandbyReply.foreach(waitForStandby(_))
        done(true)
      case None ⇒
        app.eventHandler.warning(this, "Orderbook not handled by this MatchingEngine: " + order.orderbookSymbol)
        done(false)
    }
  }

  def done(status: Boolean) {
    sender ! new Rsp(status)
  }

  def waitForStandby(pendingStandbyFuture: Future[_]) {
    try {
      pendingStandbyFuture.await
    } catch {
      case e: FutureTimeoutException ⇒
        app.eventHandler.error(this, "Standby timeout: " + e)
    }
  }

}
