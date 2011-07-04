package akka.performance.trading.akka

import akka.performance.trading.common._
import akka.performance.trading.domain.Orderbook
import akka.actor.Actor._
import akka.actor.ActorRef
import akka.dispatch.MessageDispatcher
import akka.actor.PoisonPill

class AkkaTradingSystem extends TradingSystem {
  type ME = ActorRef
  type OR = ActorRef

  val orDispatcher = createOrderReceiverDispatcher
  val meDispatcher = createMatchingEngineDispatcher

  // by default we use default-dispatcher that is defined in akka.conf
  def createOrderReceiverDispatcher: Option[MessageDispatcher] = None

  // by default we use default-dispatcher that is defined in akka.conf
  def createMatchingEngineDispatcher: Option[MessageDispatcher] = None

  var matchingEngineForOrderbook: Map[String, ActorRef] = Map()

  override def createMatchingEngines = {
    var i = 0
    val pairs =
      for (orderbooks: List[Orderbook] ← orderbooksGroupedByMatchingEngine) yield {
        i = i + 1
        val me = createMatchingEngine("ME" + i, orderbooks)
        val orderbooksCopy = orderbooks map (o ⇒ Orderbook(o.symbol, true))
        val standbyOption =
          if (useStandByEngines) {
            val meStandby = createMatchingEngine("ME" + i + "s", orderbooksCopy)
            Some(meStandby)
          } else {
            None
          }

        (me, standbyOption)
      }

    Map() ++ pairs;
  }

  def createMatchingEngine(meId: String, orderbooks: List[Orderbook]) =
    actorOf(new AkkaMatchingEngine(meId, orderbooks, meDispatcher))

  override def createOrderReceivers: List[ActorRef] = {
    val primaryMatchingEngines = matchingEngines.map(pair ⇒ pair._1).toList
    (1 to 10).toList map (i ⇒ createOrderReceiver(primaryMatchingEngines))
  }

  def createOrderReceiver(matchingEngines: List[ActorRef]) =
    actorOf(new AkkaOrderReceiver(matchingEngines, orDispatcher))

  override def start() {
    for ((p, s) ← matchingEngines) {
      p.start()
      // standby is optional
      s.foreach(_.start())
      s.foreach(p ! _)
    }
    orderReceivers.foreach(_.start())
  }

  override def shutdown() {
    orderReceivers.foreach(_ ! PoisonPill)
    for ((p, s) ← matchingEngines) {
      p ! PoisonPill
      // standby is optional
      s.foreach(_ ! PoisonPill)
    }
  }
}
