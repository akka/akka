/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.amqp.test

import se.scalablesolutions.akka.amqp.AMQP
import se.scalablesolutions.akka.actor.ActorRegistry
import java.util.concurrent.TimeUnit

object AMQPTest {
  
  def enabled = true

  def withCleanEndState(action: => Unit) {
    try {
      action
    } finally {
      AMQP.shutdownAll
    }
  }
}
