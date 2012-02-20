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
import com.rabbitmq.client.Address
import akka.actor.{ Props, ActorSystem, Actor }
import akka.event.Logging

class AMQPConnectionShutdownTestIntegration extends JUnitSuite with MustMatchers {

  @Test
  def connectionShutdown = AMQPTest.withCleanEndState {

    val system = ActorSystem.create
    val log = Logging(system, "AMQPConnectionShutdownTestIntegration")

    // latches start out closed
    val connectedLatch = new StandardLatch
    val reconnectedLatch = new StandardLatch
    val reconnectingLatch = new StandardLatch
    val disconnectedLatch = new StandardLatch

    val connectionCallback = system.actorOf(Props(new Actor {
      def receive = {
        case Connected ⇒ {
          log.info("connectionCallback received Connected message")
          if (!connectedLatch.isOpen) {
            connectedLatch.open
          } else {
            reconnectedLatch.open
          }
        }
        case Reconnecting ⇒ {
          log.info("connectionCallback received Reconnecting message")
          reconnectingLatch.open
        }
        case Disconnected ⇒ {
          log.info("connectionCallback received Disconnected message")
          disconnectedLatch.open
        }
      }
    }))

    // second address is default local rabbitmq instance, tests multiple address connection
    val localAddresses = Array(new Address("localhost", 9999), new Address("localhost", 5672))
    val connection = AMQP.newConnection(ConnectionParameters(addresses = localAddresses, initReconnectDelay = 50, connectionCallback = Some(connectionCallback)))
    try {
      connectedLatch.tryAwait(2, TimeUnit.SECONDS) must be(true)
      log.info("connectedLatch test success")
      AMQP.shutdownConnection(connection)
      log.info("sent Disconnect")
    } finally {
      reconnectingLatch.tryAwait(2, TimeUnit.SECONDS) must be(false)
      reconnectedLatch.tryAwait(2, TimeUnit.SECONDS) must be(false)
      disconnectedLatch.tryAwait(2, TimeUnit.SECONDS) must be(true)
    }
  }

}
