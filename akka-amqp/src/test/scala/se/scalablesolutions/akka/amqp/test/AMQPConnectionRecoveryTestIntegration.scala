package se.scalablesolutions.akka.amqp.test

/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

import java.util.concurrent.TimeUnit
import se.scalablesolutions.akka.actor.{Actor, ActorRef}
import org.multiverse.api.latches.StandardLatch
import com.rabbitmq.client.ShutdownSignalException
import se.scalablesolutions.akka.amqp._
import se.scalablesolutions.akka.amqp.AMQP.ConnectionParameters
import org.scalatest.matchers.MustMatchers
import org.scalatest.junit.JUnitSuite
import org.junit.Test

class AMQPConnectionRecoveryTestIntegration extends JUnitSuite with MustMatchers {

  @Test
  def connectionAndRecovery = AMQPTest.withCleanEndState {

    val connectedLatch = new StandardLatch
    val reconnectingLatch = new StandardLatch
    val reconnectedLatch = new StandardLatch
    val disconnectedLatch = new StandardLatch

    val connectionCallback: ActorRef = Actor.actorOf( new Actor {
      def receive = {
        case Connected =>
          if (!connectedLatch.isOpen) {
            connectedLatch.open
          } else {
            reconnectedLatch.open
          }
        case Reconnecting => reconnectingLatch.open
        case Disconnected => disconnectedLatch.open
      }
    }).start

    val connection = AMQP.newConnection(ConnectionParameters(initReconnectDelay = 50, connectionCallback = Some(connectionCallback)))
    try {
      connectedLatch.tryAwait(2, TimeUnit.SECONDS) must be(true)

      connection ! new ConnectionShutdown(new ShutdownSignalException(true, false, "TestException", "TestRef"))
      reconnectingLatch.tryAwait(2, TimeUnit.SECONDS) must be(true)
      reconnectedLatch.tryAwait(2, TimeUnit.SECONDS) must be(true)

    } finally {
      AMQP.shutdownAll
      disconnectedLatch.tryAwait(2, TimeUnit.SECONDS) must be(true)
    }
  }

}
