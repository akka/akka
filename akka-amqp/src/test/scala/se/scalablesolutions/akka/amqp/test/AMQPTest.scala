/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.amqp.test

import se.scalablesolutions.akka.amqp.AMQP

object AMQPTest {

  def withCleanEndState(action: => Unit) {
    try {
      try {
        action
      } finally {
        AMQP.shutdownAll
      }
    } catch {
      case e => println(e)
    }
  }
}
