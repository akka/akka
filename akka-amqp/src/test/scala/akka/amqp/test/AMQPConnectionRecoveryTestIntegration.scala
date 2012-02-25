/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.amqp.test

import akka.amqp._
import org.scalatest.matchers.MustMatchers
import org.scalatest.junit.JUnitSuite
import org.junit.Test
import com.rabbitmq.client.{ Address, ShutdownSignalException }
import akka.pattern.ask
import akka.testkit.TestLatch
import akka.dispatch.Await
import akka.actor._
import akka.util.Timeout

class AMQPConnectionRecoveryTestIntegration extends JUnitSuite with MustMatchers {

  @Test
  def connectionAndRecovery = AMQPTest.withCleanEndState((sys: ActorSystem) ⇒ {

    //TODO is there a cleaner way to handle this implicit declaration?
    implicit val system = sys
    val settings = Settings(system)
    implicit val timeout = Timeout(settings.Timeout)

    val connectedLatch = new TestLatch
    val reconnectingLatch = new TestLatch
    val reconnectedLatch = new TestLatch
    val disconnectedLatch = new TestLatch

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

    // get an AMQP root actor
    val amqp = system.actorOf(Props[AMQPActor])

    // second address is default local rabbitmq instance, tests multiple address connection
    val localAddresses = Array(new Address("localhost", 9999), new Address("localhost", 5672))
    val connectionFuture = (amqp ? ConnectionRequest(ConnectionParameters(addresses = localAddresses, initReconnectDelay = 50, connectionCallback = Some(connectionCallback)))) mapTo manifest[ActorRef]
    val connection = Await.result(connectionFuture, timeout.duration)

    try {
      Await result (connectedLatch, timeout.duration)
      connection ! new ConnectionShutdown(new ShutdownSignalException(true, false, "TestException", "TestRef"))
      Await result (reconnectingLatch, timeout.duration)
      Await result (reconnectedLatch, timeout.duration)
    } catch {
      case e: Exception ⇒ fail(e)
    } finally {
      connection ! PoisonPill
      Await result (disconnectedLatch, timeout.duration)
    }
  })

}
