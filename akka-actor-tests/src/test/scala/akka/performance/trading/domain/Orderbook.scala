package akka.performance.trading.domain

abstract class Orderbook(val symbol: String) {
  var bidSide: List[Bid] = Nil
  var askSide: List[Ask] = Nil

  def addOrder(order: Order) {
    assert(symbol == order.orderbookSymbol)
    order match {
      case bid: Bid ⇒
        bidSide = (bid :: bidSide).sortWith(_.price > _.price)
      case ask: Ask ⇒
        askSide = (ask :: askSide).sortWith(_.price < _.price)
    }
  }

  // this is by intention not tuned for performance to simulate some work
  def matchOrders() {
    if (!bidSide.isEmpty && !askSide.isEmpty) {
      val topOfBook = (bidSide.head, askSide.head)
      topOfBook match {
        case (bid, ask) if bid.price < ask.price ⇒ // no match
        case (bid, ask) if bid.price >= ask.price && bid.volume == ask.volume ⇒
          trade(bid, ask)
          bidSide = bidSide.tail
          askSide = askSide.tail
          matchOrders
        case (bid, ask) if bid.price >= ask.price && bid.volume < ask.volume ⇒
          val matchingAsk = ask.split(bid.volume)
          val remainingAsk = ask.split(ask.volume - bid.volume)
          trade(bid, matchingAsk)
          bidSide = bidSide.tail
          askSide = remainingAsk :: askSide.tail
          matchOrders
        case (bid, ask) if bid.price >= ask.price && bid.volume > ask.volume ⇒
          val matchingBid = bid.split(ask.volume)
          val remainingBid = bid.split(bid.volume - ask.volume)
          trade(matchingBid, ask)
          bidSide = remainingBid :: bidSide.tail
          askSide = askSide.tail
          matchOrders
      }
    }
  }

  def trade(bid: Bid, ask: Ask)

}

object Orderbook {

  val useDummyOrderbook = System.getProperty("benchmark.useDummyOrderbook", "false").toBoolean

  def apply(symbol: String, standby: Boolean): Orderbook = standby match {
    case false if !useDummyOrderbook ⇒ new Orderbook(symbol) with SimpleTradeObserver
    case true if !useDummyOrderbook  ⇒ new Orderbook(symbol) with StandbyTradeObserver
    case false if useDummyOrderbook  ⇒ new DummyOrderbook(symbol) with SimpleTradeObserver
    case true if useDummyOrderbook   ⇒ new DummyOrderbook(symbol) with StandbyTradeObserver
  }
}

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
