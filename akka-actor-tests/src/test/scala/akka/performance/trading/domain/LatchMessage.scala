package akka.performance.trading.domain

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

trait LatchMessage {
  val count: Int
  lazy val latch: CountDownLatch = new CountDownLatch(count)
}

object LatchOrder {
  def apply(order: Order) = order match {
    case bid: Bid ⇒ new Bid(order.orderbookSymbol, order.price, order.volume) with LatchMessage { val count = 2 }
    case ask: Ask ⇒ new Ask(order.orderbookSymbol, order.price, order.volume) with LatchMessage { val count = 2 }
  }
}