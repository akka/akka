/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import language.postfixOps

import org.scalatest.matchers.MustMatchers
import scala.concurrent.util.duration._
import org.apache.camel.ProducerTemplate
import akka.actor._
import akka.util.Timeout
import TestSupport._
import org.scalatest.WordSpec
import akka.testkit.TestLatch
import scala.concurrent.Await

class ActivationIntegrationTest extends WordSpec with MustMatchers with SharedCamelSystem {
  implicit val timeout = Timeout(10 seconds)
  def template: ProducerTemplate = camel.template

  def testActorWithEndpoint(uri: String): ActorRef = { system.actorOf(Props(new TestConsumer(uri))) }

  "ActivationAware must be notified when endpoint is activated" in {
    val actor = testActorWithEndpoint("direct:actor-1")
    try {
      camel.awaitActivation(actor, 1 second)
    } catch {
      case e: ActivationTimeoutException ⇒ fail("Failed to get notification within 1 second")
    }

    template.requestBody("direct:actor-1", "test") must be("received test")
  }

  "ActivationAware must be notified when endpoint is de-activated" in {
    val latch = TestLatch()
    val actor = start(new Consumer {
      def endpointUri = "direct:a3"
      def receive = { case _ ⇒ {} }

      override def postStop() {
        super.postStop()
        latch.countDown()
      }
    })
    camel.awaitActivation(actor, 1 second)

    system.stop(actor)
    camel.awaitDeactivation(actor, 1 second)
    Await.ready(latch, 1 second)
  }

  "ActivationAware must time out when waiting for endpoint de-activation for too long" in {
    val actor = start(new TestConsumer("direct:a5"))
    camel.awaitActivation(actor, 1 second)
    intercept[DeActivationTimeoutException] {
      camel.awaitDeactivation(actor, 1 millis)
    }
  }

  "awaitActivation must fail if notification timeout is too short and activation is not complete yet" in {
    val actor = testActorWithEndpoint("direct:actor-4")
    intercept[ActivationTimeoutException] {
      camel.awaitActivation(actor, 1 millis)
    }
  }

  class TestConsumer(uri: String) extends Consumer {
    def endpointUri = uri
    override def receive = {
      case msg: CamelMessage ⇒ sender ! "received " + msg.body
    }
  }

}