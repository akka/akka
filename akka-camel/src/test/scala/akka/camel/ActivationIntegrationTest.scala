/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.camel

import language.postfixOps

import org.scalatest.Matchers
import scala.concurrent.duration._
import org.apache.camel.ProducerTemplate
import akka.actor._
import TestSupport._
import org.scalatest.WordSpec
import akka.testkit.TestLatch
import scala.concurrent.Await
import java.util.concurrent.TimeoutException
import akka.util.Timeout

class ActivationIntegrationTest extends WordSpec with Matchers with SharedCamelSystem {
  val timeoutDuration = 10 seconds
  implicit val timeout = Timeout(timeoutDuration)
  def template: ProducerTemplate = camel.template
  import system.dispatcher

  "ActivationAware should be notified when endpoint is activated" in {
    val latch = new TestLatch(0)
    val actor = system.actorOf(Props(new TestConsumer("direct:actor-1", latch)), "act-direct-actor-1")
    Await.result(camel.activationFutureFor(actor), 10 seconds) should ===(actor)

    template.requestBody("direct:actor-1", "test") should ===("received test")
  }

  "ActivationAware should be notified when endpoint is de-activated" in {
    val latch = TestLatch(1)
    val actor = start(new Consumer {
      def endpointUri = "direct:a3"
      def receive = { case _ ⇒ {} }

      override def postStop(): Unit = {
        super.postStop()
        latch.countDown()
      }
    }, name = "direct-a3")
    Await.result(camel.activationFutureFor(actor), timeoutDuration)

    system.stop(actor)
    Await.result(camel.deactivationFutureFor(actor), timeoutDuration)
    Await.ready(latch, timeoutDuration)
  }

  "ActivationAware must time out when waiting for endpoint de-activation for too long" in {
    val latch = new TestLatch(0)
    val actor = start(new TestConsumer("direct:a5", latch), name = "direct-a5")
    Await.result(camel.activationFutureFor(actor), timeoutDuration)
    intercept[TimeoutException] { Await.result(camel.deactivationFutureFor(actor), 1 millis) }
  }

  "activationFutureFor must fail if notification timeout is too short and activation is not complete yet" in {
    val latch = new TestLatch(1)
    val actor = system.actorOf(Props(new TestConsumer("direct:actor-4", latch)), "direct-actor-4")
    intercept[TimeoutException] { Await.result(camel.activationFutureFor(actor), 1 millis) }
    latch.countDown()
    // after the latch is removed, complete the wait for completion so this test does not later on
    // print errors because of the registerConsumer timing out.
    Await.result(camel.activationFutureFor(actor), timeoutDuration)
  }

  class TestConsumer(uri: String, latch: TestLatch) extends Consumer {
    def endpointUri = uri

    override def preStart(): Unit = {
      Await.ready(latch, 60 seconds)
      super.preStart()
    }

    override def receive = {
      case msg: CamelMessage ⇒ sender() ! "received " + msg.body
    }
  }

}
