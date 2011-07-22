package akka.performance.trading.common

import akka.performance.trading.domain.Orderbook
import akka.performance.trading.domain.OrderbookRepository
import akka.actor.Actor._
import akka.actor.ActorRef
import akka.actor.PoisonPill
import akka.dispatch.MessageDispatcher

trait TradingSystem {
  type ME
  type OR

  val allOrderbookSymbols: List[String] = OrderbookRepository.allOrderbookSymbols

  val orderbooksGroupedByMatchingEngine: List[List[Orderbook]] =
    for (groupOfSymbols: List[String] ← OrderbookRepository.orderbookSymbolsGroupedByMatchingEngine)
      yield groupOfSymbols map (s ⇒ Orderbook(s, false))

  def useStandByEngines: Boolean = true

  lazy val matchingEngines: List[MatchingEngineInfo] = createMatchingEngines

  def createMatchingEngines: List[MatchingEngineInfo]

  lazy val orderReceivers: List[OR] = createOrderReceivers

  def createOrderReceivers: List[OR]

  def start()

  def shutdown()

  case class MatchingEngineInfo(primary: ME, standby: Option[ME], orderbooks: List[Orderbook])
}

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

  override def createMatchingEngines: List[MatchingEngineInfo] = {
    for {
      (orderbooks, i) ← orderbooksGroupedByMatchingEngine.zipWithIndex
      n = i + 1
    } yield {
      val me = createMatchingEngine("ME" + n, orderbooks)
      val orderbooksCopy = orderbooks map (o ⇒ Orderbook(o.symbol, true))
      val standbyOption =
        if (useStandByEngines) {
          val meStandby = createMatchingEngine("ME" + n + "s", orderbooksCopy)
          Some(meStandby)
        } else {
          None
        }

      MatchingEngineInfo(me, standbyOption, orderbooks)
    }
  }

  def createMatchingEngine(meId: String, orderbooks: List[Orderbook]) =
    actorOf(new AkkaMatchingEngine(meId, orderbooks, meDispatcher))

  override def createOrderReceivers: List[ActorRef] = {
    (1 to 10).toList map (i ⇒ createOrderReceiver())
  }

  def matchingEngineRouting: MatchingEngineRouting[ActorRef] = {
    val rules =
      for {
        info ← matchingEngines
        orderbookSymbols = info.orderbooks.map(_.symbol)
      } yield {
        (info.primary, orderbookSymbols)
      }

    MatchingEngineRouting(Map() ++ rules)
  }

  def createOrderReceiver() =
    actorOf(new AkkaOrderReceiver(orDispatcher))

  override def start() {
    for (MatchingEngineInfo(p, s, o) ← matchingEngines) {
      p.start()
      // standby is optional
      s.foreach(_.start())
      s.foreach(p ! _)
    }
    val routing = matchingEngineRouting
    for (or ← orderReceivers) {
      or.start()
      or ! routing
    }
  }

  override def shutdown() {
    orderReceivers.foreach(_ ! PoisonPill)
    for (MatchingEngineInfo(p, s, o) ← matchingEngines) {
      p ! PoisonPill
      // standby is optional
      s.foreach(_ ! PoisonPill)
    }
  }
}
