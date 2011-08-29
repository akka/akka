package akka.performance.trading.common

import org.junit._
import akka.performance.trading.domain._

trait BenchmarkScenarios extends PerformanceTest {

  @Test
  def complexScenario1 = complexScenario(1)
  @Test
  def complexScenario2 = complexScenario(2)
  @Test
  def complexScenario4 = complexScenario(4)
  @Test
  def complexScenario6 = complexScenario(6)
  @Test
  def complexScenario8 = complexScenario(8)
  @Test
  def complexScenario10 = complexScenario(10)
  @Test
  def complexScenario20 = complexScenario(20)
  @Test
  def complexScenario30 = complexScenario(30)
  @Test
  def complexScenario40 = complexScenario(40)
  @Test
  def complexScenario60 = complexScenario(60)
  @Test
  def complexScenario80 = complexScenario(80)
  @Test
  def complexScenario100 = complexScenario(100)
  /*
  @Test
  def complexScenario200 = complexScenario(200)
  @Test
  def complexScenario300 = complexScenario(300)
  @Test
  def complexScenario400 = complexScenario(400)
  */

  def complexScenario(numberOfClients: Int) {
    Assume.assumeTrue(numberOfClients >= minClients)
    Assume.assumeTrue(numberOfClients <= maxClients)

    val repeat = 500 * repeatFactor

    val prefixes = "A" :: "B" :: "C" :: Nil
    val askOrders = for {
      s ← prefixes
      i ← 1 to 5
    } yield new Ask(s + i, 100 - i, 1000)
    val bidOrders = for {
      s ← prefixes
      i ← 1 to 5
    } yield new Bid(s + i, 100 - i, 1000)
    val orders = askOrders ::: bidOrders

    runScenario("benchmark", orders, repeat, numberOfClients, 0)
  }

}

