/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.amqp

sealed trait ExchangeType
object ExchangeType {
  case object Direct extends ExchangeType {
    override def toString = "direct"
  }
  case object Topic extends ExchangeType {
    override def toString = "topic"
  }
  case object Fanout extends ExchangeType {
    override def toString = "fanout"
  }
  case object Match extends ExchangeType {
    override def toString = "match"
  }
}
