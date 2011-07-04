package akka.performance.trading.common

import org.junit._
import akka.performance.trading.domain._

trait OtherPerformanceScenarios extends PerformanceTest {

  @Test
  def simpleScenario {
    val repeat = 300 * repeatFactor
    val numberOfClients = tradingSystem.orderReceivers.size

    val bid = new Bid("A1", 100, 1000)
    val ask = new Ask("A1", 100, 1000)
    val orders = bid :: ask :: Nil

    runScenario("simpleScenario", orders, repeat, numberOfClients, 0)
  }

  @Test
  def manyOrderbooks {
    val repeat = 2 * repeatFactor
    val numberOfClients = tradingSystem.orderReceivers.size

    val orderbooks = tradingSystem.allOrderbookSymbols
    val askOrders = for (o ← orderbooks) yield new Ask(o, 100, 1000)
    val bidOrders = for (o ← orderbooks) yield new Bid(o, 100, 1000)
    val orders = askOrders ::: bidOrders

    runScenario("manyOrderbooks", orders, repeat, numberOfClients, 5)
  }

  @Test
  def manyClients {
    val repeat = 1 * repeatFactor
    val numberOfClients = tradingSystem.orderReceivers.size * 10

    val orderbooks = tradingSystem.allOrderbookSymbols
    val askOrders = for (o ← orderbooks) yield new Ask(o, 100, 1000)
    val bidOrders = for (o ← orderbooks) yield new Bid(o, 100, 1000)
    val orders = askOrders ::: bidOrders

    runScenario("manyClients", orders, repeat, numberOfClients, 5)
  }

  @Test
  def oneClient {
    val repeat = 10000 * repeatFactor
    val numberOfClients = 1

    val bid = new Bid("A1", 100, 1000)
    val ask = new Ask("A1", 100, 1000)
    val orders = bid :: ask :: Nil

    runScenario("oneClient", orders, repeat, numberOfClients, 0)
  }

  @Test
  def oneSlowClient {
    val repeat = 300 * repeatFactor
    val numberOfClients = 1

    val bid = new Bid("A1", 100, 1000)
    val ask = new Ask("A1", 100, 1000)
    val orders = bid :: ask :: Nil

    runScenario("oneSlowClient", orders, repeat, numberOfClients, 5)
  }

}