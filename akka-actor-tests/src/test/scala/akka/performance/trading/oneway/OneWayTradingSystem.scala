package akka.performance.trading.oneway

import akka.performance.trading.common.AkkaTradingSystem
import akka.performance.trading.domain.Orderbook
import akka.actor.{ Props, ActorRef }
import akka.AkkaApplication

class OneWayTradingSystem(_app: AkkaApplication) extends AkkaTradingSystem(_app) {

  override def createMatchingEngine(meId: String, orderbooks: List[Orderbook]) = meDispatcher match {
    case Some(d) ⇒ app.createActor(Props(new OneWayMatchingEngine(meId, orderbooks)).withDispatcher(d))
    case _       ⇒ app.createActor(Props(new OneWayMatchingEngine(meId, orderbooks)))
  }

  override def createOrderReceiver() = orDispatcher match {
    case Some(d) ⇒ app.createActor(Props[OneWayOrderReceiver].withDispatcher(d))
    case _       ⇒ app.createActor(Props[OneWayOrderReceiver])
  }

}
