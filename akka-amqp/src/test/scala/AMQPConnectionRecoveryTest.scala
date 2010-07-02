/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.amqp.test

import se.scalablesolutions.akka.util.Logging
import org.scalatest.junit.JUnitSuite
import org.junit.Test
import java.util.concurrent.TimeUnit
import se.scalablesolutions.akka.actor.{Actor, ActorRef}
import org.multiverse.api.latches.StandardLatch
import com.rabbitmq.client.ShutdownSignalException
import se.scalablesolutions.akka.amqp._
import se.scalablesolutions.akka.amqp.AMQP.ConnectionParameters
import org.scalatest.matchers.MustMatchers

class AMQPConnectionRecoveryTest extends JUnitSuite with MustMatchers with Logging {

//  @Test
  def connectionAndRecovery = {
    val connectedLatch = new StandardLatch
    val reconnectingLatch = new StandardLatch
    val reconnectedLatch = new StandardLatch
    val disconnectedLatch = new StandardLatch

    val connectionCallback: ActorRef = Actor.actor({
      case Connected =>
        if (!connectedLatch.isOpen) {
          connectedLatch.open
        } else {
          reconnectedLatch.open
        }
      case Reconnecting => reconnectingLatch.open
      case Disconnected => disconnectedLatch.open
    })

    val connection = AMQP.newConnection(ConnectionParameters(initReconnectDelay = 50, connectionCallback = Some(connectionCallback)))
    try {
      connectedLatch.tryAwait(2, TimeUnit.SECONDS) must be(true)

      connection ! new ConnectionShutdown(new ShutdownSignalException(true, false, "TestException", "TestRef"))
      reconnectingLatch.tryAwait(2, TimeUnit.SECONDS) must be(true)
      reconnectedLatch.tryAwait(2, TimeUnit.SECONDS) must be(true)

    } finally {
      connection.stop
      disconnectedLatch.tryAwait(2, TimeUnit.SECONDS) must be(true)
    }
  }

  @Test
  def dummy {
    // amqp tests need local rabbitmq server running, so a disabled by default.
    // this dummy test makes sure that the whole test class doesn't fail because of missing tests
    assert(true)
  }
}