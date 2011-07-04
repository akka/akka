package akka.performance.trading.akka

import akka.actor._
import akka.dispatch.Future
import akka.dispatch.FutureTimeoutException
import akka.dispatch.MessageDispatcher

import akka.performance.trading.common.MatchingEngine
import akka.performance.trading.domain._
import akka.performance.trading.domain.SupportedOrderbooksReq
import akka.dispatch.MessageDispatcher
import akka.actor.ActorRef

class AkkaMatchingEngine(val meId: String, val orderbooks: List[Orderbook], disp: Option[MessageDispatcher]) extends Actor with MatchingEngine {
  for (d ← disp) {
    self.dispatcher = d
  }

  var standby: Option[ActorRef] = None

  def receive = {
    case standbyRef: ActorRef ⇒
      standby = Some(standbyRef)
    case SupportedOrderbooksReq ⇒
      self.channel ! orderbooks
    case order: Order ⇒
      handleOrder(order)
    case unknown ⇒
      println("Received unknown message: " + unknown)
  }

  def handleOrder(order: Order) {
    orderbooksMap.get(order.orderbookSymbol) match {
      case Some(orderbook) ⇒
        // println(meId + " " + order)

        val pendingStandbyReply: Option[Future[_]] =
          for (s ← standby) yield { s ? order }

        txLog.storeTx(order)
        orderbook.addOrder(order)
        orderbook.matchOrders()
        // wait for standby reply
        pendingStandbyReply.foreach(waitForStandby(_))
        self.channel ! new Rsp(true)
      case None ⇒
        println("Orderbook not handled by this MatchingEngine: " + order.orderbookSymbol)
        self.channel ! new Rsp(false)
    }
  }

  override def postStop {
    txLog.close()
  }

  def waitForStandby(pendingStandbyFuture: Future[_]) {
    try {
      pendingStandbyFuture.await
    } catch {
      case e: FutureTimeoutException ⇒ println("### standby timeout: " + e)
    }
  }

}
