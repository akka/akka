package akka.amqp.test

/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

import java.util.concurrent.TimeUnit
import org.multiverse.api.latches.StandardLatch
import akka.amqp._
import akka.amqp.AMQP.ConnectionParameters
import org.scalatest.matchers.MustMatchers
import org.scalatest.junit.JUnitSuite
import org.junit.Test
import com.rabbitmq.client.{ Address, ShutdownSignalException }
import akka.actor.{ Props, ActorSystem, Actor }

class AMQPConnectionRecoveryTestIntegration extends JUnitSuite with MustMatchers {

  @Test
  def connectionAndRecovery = AMQPTest.withCleanEndState {

    val system = ActorSystem.create

    val connectedLatch = new StandardLatch
    val reconnectingLatch = new StandardLatch
    val reconnectedLatch = new StandardLatch
    val disconnectedLatch = new StandardLatch

    val connectionCallback = system.actorOf(Props(new Actor {
      def receive = {
        case Connected ⇒
          if (!connectedLatch.isOpen) {
            connectedLatch.open
          } else {
            reconnectedLatch.open
          }
        case Reconnecting ⇒ reconnectingLatch.open
        case Disconnected ⇒ disconnectedLatch.open
      }
    }))

    // second address is default local rabbitmq instance, tests multiple address connection
    val localAddresses = Array(new Address("localhost", 9999), new Address("localhost", 5672))
    val connection = AMQP.newConnection(ConnectionParameters(addresses = localAddresses, initReconnectDelay = 50, connectionCallback = Some(connectionCallback)))
    try {
      connectedLatch.tryAwait(2, TimeUnit.SECONDS) must be(true)

      connection ! new ConnectionShutdown(new ShutdownSignalException(true, false, "TestException", "TestRef"))
      reconnectingLatch.tryAwait(2, TimeUnit.SECONDS) must be(true)
      reconnectedLatch.tryAwait(2, TimeUnit.SECONDS) must be(true)

    } finally {
      AMQP.shutdownConnection(connection)
      disconnectedLatch.tryAwait(2, TimeUnit.SECONDS) must be(true)
    }
  }

}
