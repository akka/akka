/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import language.postfixOps

import org.scalatest.matchers.MustMatchers
import scala.concurrent.util.duration._
import org.apache.camel.ProducerTemplate
import akka.actor._
import TestSupport._
import org.scalatest.WordSpec
import akka.testkit.TestLatch
import concurrent.Await
import java.util.concurrent.TimeoutException

class ActivationIntegrationTest extends WordSpec with MustMatchers with SharedCamelSystem {
  implicit val timeout = 10 seconds
  def template: ProducerTemplate = camel.template
  import system.dispatcher

  "ActivationAware must be notified when endpoint is activated" in {
    val latch = new TestLatch(0)
    val actor = system.actorOf(Props(new TestConsumer("direct:actor-1", latch)), "act-direct-actor-1")
    Await.result(camel.activationFutureFor(actor), 10 seconds) must be === actor

    template.requestBody("direct:actor-1", "test") must be("received test")
  }

  "ActivationAware must be notified when endpoint is de-activated" in {
    val latch = TestLatch(1)
    val actor = start(new Consumer {
      def endpointUri = "direct:a3"
      def receive = { case _ ⇒ {} }

      override def postStop() {
        super.postStop()
        latch.countDown()
      }
    }, name = "direct-a3")
    Await.result(camel.activationFutureFor(actor), timeout)

    system.stop(actor)
    Await.result(camel.deactivationFutureFor(actor), timeout)
    Await.ready(latch, timeout)
  }

  "ActivationAware must time out when waiting for endpoint de-activation for too long" in {
    val latch = new TestLatch(0)
    val actor = start(new TestConsumer("direct:a5", latch), name = "direct-a5")
    Await.result(camel.activationFutureFor(actor), timeout)
    intercept[TimeoutException] { Await.result(camel.deactivationFutureFor(actor), 1 millis) }
  }

  "activationFutureFor must fail if notification timeout is too short and activation is not complete yet" in {
    val latch = new TestLatch(1)
    val actor = system.actorOf(Props(new TestConsumer("direct:actor-4", latch)), "direct-actor-4")
    try {
      intercept[TimeoutException] { Await.result(camel.activationFutureFor(actor), 1 millis) }
    } finally {
      latch.countDown()
      // after the latch is removed, complete the wait for completion so this test does not later on
      // print errors because of the registerConsumer timing out.
      Await.result(camel.activationFutureFor(actor), timeout)
    }
  }

  class TestConsumer(uri: String, latch: TestLatch) extends Consumer {
    def endpointUri = uri

    override def preStart() {
      Await.ready(latch, 60 seconds)
      super.preStart()
    }

    override def receive = {
      case msg: CamelMessage ⇒ sender ! "received " + msg.body
    }
  }

}