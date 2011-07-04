package akka.performance.trading.common

import akka.performance.trading.domain.Orderbook

trait MatchingEngine {
  val meId: String
  val orderbooks: List[Orderbook]
  val supportedOrderbookSymbols = orderbooks map (_.symbol)
  protected val orderbooksMap: Map[String, Orderbook] =
    Map() ++ (orderbooks map (o ⇒ (o.symbol, o)))

  protected val txLog: TxLog =
    if (useTxLogFile)
      new TxLogFile(meId + ".txlog")
    else
      new TxLogDummy

  def useTxLogFile() = {
    val prop = System.getProperty("useTxLogFile")
    // default false, if not defined
    (prop == "true")
  }
}
