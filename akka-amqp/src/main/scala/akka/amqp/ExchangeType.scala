/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.amqp

sealed trait ExchangeType
case object Direct extends ExchangeType {
  def getInstance() = this // Needed for Java API usage
  override def toString = "direct"
}
case object Topic extends ExchangeType {
  def getInstance() = this // Needed for Java API usage
  override def toString = "topic"
}
case object Fanout extends ExchangeType {
  def getInstance() = this // Needed for Java API usage
  override def toString = "fanout"
}
case object Match extends ExchangeType {
  def getInstance() = this // Needed for Java API usage
  override def toString = "match"
}

case class CustomExchange(exchangeType: String) extends ExchangeType {
  override def toString = exchangeType
}
