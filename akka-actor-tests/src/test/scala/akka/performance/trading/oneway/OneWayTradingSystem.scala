package akka.performance.trading.oneway

import akka.actor.Actor.actorOf
import akka.performance.trading.common.AkkaTradingSystem
import akka.performance.trading.domain.Orderbook
import akka.actor.{ Props, ActorRef }

class OneWayTradingSystem extends AkkaTradingSystem {

  override def createMatchingEngine(meId: String, orderbooks: List[Orderbook]) = meDispatcher match {
    case Some(d) ⇒ actorOf(Props(new OneWayMatchingEngine(meId, orderbooks)).withDispatcher(d))
    case _       ⇒ actorOf(Props(new OneWayMatchingEngine(meId, orderbooks)))
  }

  override def createOrderReceiver() = orDispatcher match {
    case Some(d) ⇒ actorOf(Props[OneWayOrderReceiver].withDispatcher(d))
    case _       ⇒ actorOf(Props[OneWayOrderReceiver])
  }

}
