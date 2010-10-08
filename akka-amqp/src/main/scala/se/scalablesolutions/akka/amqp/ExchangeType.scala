/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.amqp

sealed trait ExchangeType
object ExchangeType {
  case class Direct() extends ExchangeType {
    override def toString = "direct"
  }
  case class Topic() extends ExchangeType {
    override def toString = "topic"
  }
  case class Fanout() extends ExchangeType {
    override def toString = "fanout"
  }
  case class Match() extends ExchangeType {
    override def toString = "match"
  }
}
