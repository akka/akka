package akka.performance.trading.domain

abstract class DummyOrderbook(symbol: String) extends Orderbook(symbol) {
  var count = 0
  var bid: Bid = _
  var ask: Ask = _

  override def addOrder(order: Order) {
    count += 1
    order match {
      case b: Bid ⇒ bid = b
      case a: Ask ⇒ ask = a
    }
  }

  override def matchOrders() {
    if (count % 2 == 0)
      trade(bid, ask)
  }

  def trade(bid: Bid, ask: Ask)

}
