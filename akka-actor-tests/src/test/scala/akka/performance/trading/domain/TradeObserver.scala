package akka.performance.trading.domain

import java.util.concurrent.atomic.AtomicInteger

abstract trait TradeObserver {
  def trade(bid: Bid, ask: Ask)
}

trait SimpleTradeObserver extends TradeObserver {
  override def trade(bid: Bid, ask: Ask) {
    val c = TotalTradeCounter.counter.incrementAndGet
  }
}

trait StandbyTradeObserver extends TradeObserver {
  override def trade(bid: Bid, ask: Ask) {
  }
}

object TotalTradeCounter {
  val counter = new AtomicInteger

  def reset() {
    counter.set(0)
  }
}