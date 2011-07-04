package akka.performance.trading.domain

object OrderbookRepository {
  def allOrderbookSymbols: List[String] = {
    val prefix = "A" :: "B" :: "C" :: "D" :: "E" :: "F" :: "G" :: "H" :: "I" :: "J" :: Nil
    for {
      p ← prefix
      i ← 1 to 10
    } yield p + i
  }

  def orderbookSymbolsGroupedByMatchingEngine: List[List[String]] = {
    val groupMap = allOrderbookSymbols groupBy (_.charAt(0))
    groupMap.map(entry ⇒ entry._2).toList
  }

}
