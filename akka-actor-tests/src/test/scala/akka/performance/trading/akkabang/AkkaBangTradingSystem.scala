package akka.performance.trading.akkabang

import akka.performance.trading.akka._
import akka.performance.trading.domain.Orderbook
import akka.actor.Actor._
import akka.actor.ActorRef

class AkkaBangTradingSystem extends AkkaTradingSystem {

  override def createMatchingEngine(meId: String, orderbooks: List[Orderbook]) =
    actorOf(new AkkaBangMatchingEngine(meId, orderbooks, meDispatcher))

  override def createOrderReceiver(matchingEngines: List[ActorRef]) =
    actorOf(new AkkaBangOrderReceiver(matchingEngines, orDispatcher))

}