package akka.performance.trading.domain

import java.util.concurrent.atomic.AtomicInteger

object TotalTradeCounter {
  val counter = new AtomicInteger

  def reset() {
    counter.set(0)
  }
}
