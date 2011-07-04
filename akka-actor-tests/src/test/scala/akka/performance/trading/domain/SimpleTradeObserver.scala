package akka.performance.trading.domain

trait SimpleTradeObserver extends TradeObserver {
  override def trade(bid: Bid, ask: Ask) {
    val c = TotalTradeCounter.counter.incrementAndGet
    //		println("trade " + c + " " + bid + " -- " + ask)
  }
}

