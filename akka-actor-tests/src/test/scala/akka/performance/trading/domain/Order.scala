package akka.performance.trading.domain

trait Order {
  def orderbookSymbol: String
  def price: Long
  def volume: Long
  def nanoTime: Long
  def withNanoTime: Order
}

case class Bid(
  orderbookSymbol: String,
  price: Long,
  volume: Long,
  nanoTime: Long = 0L)
  extends Order {

  def split(newVolume: Long) = {
    new Bid(orderbookSymbol, price, newVolume)
  }

  def withNanoTime: Bid = copy(nanoTime = System.nanoTime)
}

case class Ask(
  orderbookSymbol: String,
  price: Long,
  volume: Long,
  nanoTime: Long = 0L)
  extends Order {

  def split(newVolume: Long) = {
    new Ask(orderbookSymbol, price, newVolume)
  }

  def withNanoTime: Ask = copy(nanoTime = System.nanoTime)
}
