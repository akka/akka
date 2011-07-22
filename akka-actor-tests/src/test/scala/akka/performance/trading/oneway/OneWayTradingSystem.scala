package akka.performance.trading.oneway

import akka.actor.Actor.actorOf
import akka.actor.ActorRef
import akka.performance.trading.common.AkkaTradingSystem
import akka.performance.trading.domain.Orderbook

class OneWayTradingSystem extends AkkaTradingSystem {

  override def createMatchingEngine(meId: String, orderbooks: List[Orderbook]) =
    actorOf(new OneWayMatchingEngine(meId, orderbooks, meDispatcher))

  override def createOrderReceiver() =
    actorOf(new OneWayOrderReceiver(orDispatcher))

}