/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import org.scalatest.matchers.MustMatchers
import akka.util.duration._
import org.apache.camel.ProducerTemplate
import org.scalatest.FlatSpec
import akka.actor._
import akka.util.Timeout
import java.util.concurrent.{ TimeUnit, CountDownLatch }
import TestSupport._

class ActivationTest extends FlatSpec with MustMatchers with SharedCamelSystem {
  implicit val timeout = Timeout(10 seconds)
  def template: ProducerTemplate = camel.template

  def testActorWithEndpoint(uri: String): ActorRef = { system.actorOf(Props(new TestConsumer(uri))) }

  "ActivationAware" must "be notified when endpoint is activated" in {
    val actor = testActorWithEndpoint("direct:actor-1")
    try {
      camel.awaitActivation(actor, 1 second)
    } catch {
      case e: ActivationTimeoutException ⇒ fail("Failed to get notification within 1 second")
    }

    template.requestBody("direct:actor-1", "test") must be("received test")
  }

  it must "be notified when endpoint is de-activated" in {
    val stopped = new CountDownLatch(1)
    val actor = start(new Consumer {
      def endpointUri = "direct:a3"
      def receive = { case _ ⇒ {} }

      override def postStop() = {
        super.postStop()
        stopped.countDown()
      }
    })
    camel.awaitActivation(actor, 1 second)

    system.stop(actor)
    camel.awaitDeactivation(actor, 1 second)
    if (!stopped.await(1, TimeUnit.SECONDS)) fail("Actor must have stopped quickly after deactivation!")
  }

  it must "time out when waiting for endpoint de-activation for too long" in {
    val actor = start(new TestConsumer("direct:a5"))
    camel.awaitActivation(actor, 1 second)
    intercept[DeActivationTimeoutException] {
      camel.awaitDeactivation(actor, 1 millis)
    }
  }

  "awaitActivation" must "fail if notification timeout is too short and activation is not complete yet" in {
    val actor = testActorWithEndpoint("direct:actor-4")
    intercept[ActivationTimeoutException] {
      camel.awaitActivation(actor, 0 seconds)
    }
  }

  class TestConsumer(uri: String) extends Consumer {
    def endpointUri = uri
    override def receive = {
      case msg: Message ⇒ sender ! "received " + msg.body
    }
  }

}