/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.amqp.test

import akka.amqp._
import org.scalatest.matchers.MustMatchers
import org.scalatest.junit.JUnitSuite
import org.junit.Test
import com.rabbitmq.client.Address
import akka.event.Logging
import akka.util.Timeout
import akka.testkit.TestLatch
import akka.dispatch.Await
import akka.pattern.ask
import akka.actor._

class AMQPConnectionShutdownTestIntegration extends JUnitSuite with MustMatchers {

  @Test
  def connectionShutdown = AMQPTest.withCleanEndState((sys: ActorSystem) ⇒ {

    //TODO is there a cleaner way to handle this implicit declaration?
    implicit val system = sys
    val settings = Settings(system)
    implicit val timeout = Timeout(settings.Timeout)

    val log = Logging(system, "AMQPConnectionShutdownTestIntegration")

    // latches start out closed
    val connectedLatch = new TestLatch
    val reconnectedLatch = new TestLatch
    val reconnectingLatch = new TestLatch
    val disconnectedLatch = new TestLatch

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

    // get an AMQP root actor
    val amqp = system.actorOf(Props[AMQPActor])

    // second address is default local rabbitmq instance, tests multiple address connection
    val localAddresses = Array(new Address("localhost", 9999), new Address("localhost", 5672))
    val connectionFuture = (amqp ? ConnectionRequest(ConnectionParameters(addresses = localAddresses,
      initReconnectDelay = 50, connectionCallback = Some(connectionCallback)))) mapTo manifest[ActorRef]

    val connection = Await.result(connectionFuture, timeout.duration)

    try {
      Await.result(connectedLatch, timeout.duration)
      log.info("connectedLatch test success")
      connection ! PoisonPill
      log.info("sent Disconnect")
    } catch {
      case e: Exception ⇒ this.fail(e)
    }

    intercept[Exception](Await.result(reconnectedLatch, timeout.duration))
  })
}
