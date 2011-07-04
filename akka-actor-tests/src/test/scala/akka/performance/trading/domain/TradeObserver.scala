package akka.performance.trading.domain

abstract trait TradeObserver {

  def trade(bid: Bid, ask: Ask)

}
