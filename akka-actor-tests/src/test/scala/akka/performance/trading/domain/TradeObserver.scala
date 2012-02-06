package akka.performance.trading.domain

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ ExtensionIdProvider, ExtensionId, Extension, ExtendedActorSystem, ActorSystem }

abstract trait TradeObserver {
  def trade(bid: Bid, ask: Ask)
}

trait TotalTradeObserver extends TradeObserver {
  def system: ActorSystem
  private lazy val counter: TotalTradeCounter = TotalTradeCounterExtension(system)
  override def trade(bid: Bid, ask: Ask) {
    counter.increment()
  }
}

trait NopTradeObserver extends TradeObserver {
  override def trade(bid: Bid, ask: Ask) {
  }
}

class TotalTradeCounter extends Extension {
  private val counter = new AtomicInteger

  def increment() = counter.incrementAndGet()
  def reset() {
    counter.set(0)
  }
  def count: Int = counter.get
}

object TotalTradeCounterExtension
  extends ExtensionId[TotalTradeCounter]
  with ExtensionIdProvider {
  override def lookup = TotalTradeCounterExtension
  override def createExtension(system: ExtendedActorSystem) = new TotalTradeCounter
}