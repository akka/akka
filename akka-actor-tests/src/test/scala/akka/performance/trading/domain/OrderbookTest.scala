package akka.performance.trading.domain

import org.junit._
import Assert._
import org.scalatest.junit.JUnitSuite
import org.mockito.Mockito._
import org.mockito.Matchers._

class OrderbookTest extends JUnitSuite {
  var orderbook: Orderbook = null
  var tradeObserverMock: TradeObserver = null

  @Before
  def setUp = {
    tradeObserverMock = mock(classOf[TradeObserver])
    orderbook = new Orderbook("ERI") with TradeObserver {
      def trade(bid: Bid, ask: Ask) = tradeObserverMock.trade(bid, ask)
    }
  }

  @Test
  def shouldTradeSamePrice = {
    val bid = new Bid("ERI", 100, 1000)
    val ask = new Ask("ERI", 100, 1000)
    orderbook.addOrder(bid)
    orderbook.addOrder(ask)

    orderbook.matchOrders()
    assertEquals(0, orderbook.bidSide.size)
    assertEquals(0, orderbook.askSide.size)

    verify(tradeObserverMock).trade(bid, ask)
  }

  @Test
  def shouldTradeTwoLevels = {
    val bid1 = new Bid("ERI", 101, 1000)
    val bid2 = new Bid("ERI", 100, 1000)
    val bid3 = new Bid("ERI", 99, 1000)
    orderbook.addOrder(bid1)
    orderbook.addOrder(bid2)
    orderbook.addOrder(bid3)

    assertEquals(bid1 :: bid2 :: bid3 :: Nil, orderbook.bidSide)

    val ask1 = new Ask("ERI", 99, 1000)
    val ask2 = new Ask("ERI", 100, 1000)
    val ask3 = new Ask("ERI", 101, 1000)
    orderbook.addOrder(ask1)
    orderbook.addOrder(ask2)
    orderbook.addOrder(ask3)

    assertEquals(ask1 :: ask2 :: ask3 :: Nil, orderbook.askSide)

    orderbook.matchOrders()
    assertEquals(1, orderbook.bidSide.size)
    assertEquals(bid3, orderbook.bidSide.head)
    assertEquals(1, orderbook.askSide.size)
    assertEquals(ask3, orderbook.askSide.head)

    verify(tradeObserverMock, times(2)).trade(any(classOf[Bid]), any(classOf[Ask]))
  }

  @Test
  def shouldSplitBid = {
    val bid = new Bid("ERI", 100, 300)
    val ask = new Ask("ERI", 100, 1000)
    orderbook.addOrder(bid)
    orderbook.addOrder(ask)

    orderbook.matchOrders()
    assertEquals(0, orderbook.bidSide.size)
    assertEquals(1, orderbook.askSide.size)
    assertEquals(700, orderbook.askSide.head.volume)

    verify(tradeObserverMock).trade(any(classOf[Bid]), any(classOf[Ask]))
  }

  @Test
  def shouldSplitAsk = {
    val bid = new Bid("ERI", 100, 1000)
    val ask = new Ask("ERI", 100, 600)
    orderbook.addOrder(bid)
    orderbook.addOrder(ask)

    orderbook.matchOrders()
    assertEquals(1, orderbook.bidSide.size)
    assertEquals(0, orderbook.askSide.size)
    assertEquals(400, orderbook.bidSide.head.volume)

    verify(tradeObserverMock).trade(any(classOf[Bid]), any(classOf[Ask]))
  }

}