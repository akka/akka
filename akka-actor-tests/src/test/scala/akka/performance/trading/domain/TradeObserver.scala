package akka.performance.trading.domain

import java.util.concurrent.atomic.AtomicInteger

abstract trait TradeObserver {
  def trade(bid: Bid, ask: Ask)
}

trait TotalTradeObserver extends TradeObserver {
  override def trade(bid: Bid, ask: Ask) {
    TotalTradeCounter.counter.incrementAndGet
  }
}

trait NopTradeObserver extends TradeObserver {
  override def trade(bid: Bid, ask: Ask) {
  }
}

object TotalTradeCounter {
  val counter = new AtomicInteger

  def reset() {
    counter.set(0)
  }
}
