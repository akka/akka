package akka.performance.trading.system

import akka.performance.trading.domain.Orderbook
import akka.performance.trading.domain.OrderbookRepository
import akka.actor.Actor._
import akka.dispatch.MessageDispatcher
import akka.actor.{ Props, ActorRef, PoisonPill }
import akka.actor.ActorSystem

trait TradingSystem {
  type ME
  type OR

  val allOrderbookSymbols: List[String] = OrderbookRepository.allOrderbookSymbols

  def orderbooksGroupedByMatchingEngine: List[List[Orderbook]]

  def useStandByEngines: Boolean = true

  lazy val matchingEngines: List[MatchingEngineInfo] = createMatchingEngines

  def createMatchingEngines: List[MatchingEngineInfo]

  lazy val orderReceivers: List[OR] = createOrderReceivers

  def createOrderReceivers: List[OR]

  def start()

  def shutdown()

  case class MatchingEngineInfo(primary: ME, standby: Option[ME], orderbooks: List[Orderbook])
}

class AkkaTradingSystem(val system: ActorSystem) extends TradingSystem {
  type ME = ActorRef
  type OR = ActorRef

  val orDispatcher = orderReceiverDispatcher
  val meDispatcher = matchingEngineDispatcher

  def orderReceiverDispatcher: Option[String] = Some("benchmark.trading-dispatcher")

  def matchingEngineDispatcher: Option[String] = Some("benchmark.trading-dispatcher")

  override val orderbooksGroupedByMatchingEngine: List[List[Orderbook]] =
    for (groupOfSymbols: List[String] ← OrderbookRepository.orderbookSymbolsGroupedByMatchingEngine)
      yield groupOfSymbols map (s ⇒ Orderbook(s, false, system))

  var matchingEngineForOrderbook: Map[String, ActorRef] = Map()

  override def createMatchingEngines: List[MatchingEngineInfo] = {
    for {
      (orderbooks, i) ← orderbooksGroupedByMatchingEngine.zipWithIndex
      n = i + 1
    } yield {
      val me = createMatchingEngine("ME" + n, orderbooks)
      val orderbooksCopy = orderbooks map (o ⇒ Orderbook(o.symbol, true, system))
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
    meDispatcher match {
      case Some(d) ⇒ system.actorOf(Props(new AkkaMatchingEngine(meId, orderbooks)).withDispatcher(d))
      case _       ⇒ system.actorOf(Props(new AkkaMatchingEngine(meId, orderbooks)))
    }

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

  def createOrderReceiver() = orDispatcher match {
    case Some(d) ⇒ system.actorOf(Props(new AkkaOrderReceiver()).withDispatcher(d))
    case _       ⇒ system.actorOf(Props(new AkkaOrderReceiver()))
  }

  override def start() {
    for (MatchingEngineInfo(p, s, o) ← matchingEngines) {
      // standby is optional
      s.foreach(p ! _)
    }
    val routing = matchingEngineRouting
    for (or ← orderReceivers) {
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
