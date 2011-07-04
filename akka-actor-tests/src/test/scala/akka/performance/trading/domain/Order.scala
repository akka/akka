package akka.performance.trading.domain

trait Order {
  def orderbookSymbol: String
  def price: Long
  def volume: Long
}

case class Bid(
  orderbookSymbol: String,
  price: Long,
  volume: Long)
  extends Order {

  def split(newVolume: Long) = {
    new Bid(orderbookSymbol, price, newVolume)
  }
}

case class Ask(
  orderbookSymbol: String,
  price: Long,
  volume: Long)
  extends Order {

  def split(newVolume: Long) = {
    new Ask(orderbookSymbol, price, newVolume)
  }
}
