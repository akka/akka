package akka.performance.trading.common

import akka.performance.trading.domain.Orderbook

trait OrderReceiver {
  type ME
  val matchingEngines: List[ME]
  var matchingEnginePartitionsIsStale = true
  var matchingEngineForOrderbook: Map[String, ME] = Map()

  def refreshMatchingEnginePartitions() {
    val m = Map() ++
      (for {
        me ← matchingEngines
        o ← supportedOrderbooks(me)
      } yield (o.symbol, me))

    matchingEngineForOrderbook = m
    matchingEnginePartitionsIsStale = false
  }

  def supportedOrderbooks(me: ME): List[Orderbook]

}
