/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import language.postfixOps
import language.existentials

import akka.actor._
import org.scalatest.matchers.MustMatchers
import org.scalatest.WordSpec
import akka.camel.TestSupport._
import org.apache.camel.model.RouteDefinition
import org.apache.camel.builder.Builder
import org.apache.camel.{ Exchange, Processor, FailedToCreateRouteException, CamelExecutionException }
import java.util.concurrent.{ ExecutionException, TimeUnit, TimeoutException }
import akka.actor.Status.Failure
import scala.concurrent.util.duration._
import concurrent.{ ExecutionContext, Await }
import akka.testkit._
import javax.activation.DataHandler
import java.net.URL

class ConsumerIntegrationTest extends WordSpec with MustMatchers with NonSharedCamelSystem {
  "ConsumerIntegrationTest" must {
    implicit val defaultTimeout = 10.seconds
    implicit def ec: ExecutionContext = system.dispatcher

    "Consumer must throw FailedToCreateRouteException, while awaiting activation, if endpoint is invalid" in {
      filterEvents(EventFilter[ActorInitializationException](occurrences = 1), EventFilter[FailedToCreateRouteException](occurrences = 1)) {
        val actorRef = system.actorOf(Props(new TestActor(uri = "some invalid uri")), "cons-invalid-test-actor")
        intercept[FailedToCreateRouteException] {
          Await.result(camel.activationFutureFor(actorRef), defaultTimeout)
        }
      }
    }

    "Consumer must support in-out messaging" in {
      start(new Consumer {
        def endpointUri = "direct:a1"
        def receive = {
          case m: CamelMessage ⇒ sender ! "received " + m.bodyAs[String]
        }
      }, name = "direct-a1")
      camel.sendTo("direct:a1", msg = "some message") must be("received some message")
    }

    "Consumer must time-out if consumer is slow" in {
      val SHORT_TIMEOUT = 10 millis
      val LONG_WAIT = 200 millis

      val ref = start(new Consumer {
        override def replyTimeout = SHORT_TIMEOUT

        def endpointUri = "direct:a3"
        def receive = { case _ ⇒ { Thread.sleep(LONG_WAIT.toMillis); sender ! "done" } }
      }, name = "ignore-this-deadletter-timeout-consumer-reply")

      val exception = intercept[CamelExecutionException] {
        camel.sendTo("direct:a3", msg = "some msg 3")
      }
      exception.getCause.getClass must be(classOf[TimeoutException])
      stop(ref)
    }

    "Consumer must process messages even after actor restart" in {
      val restarted = TestLatch()
      val consumer = start(new Consumer {
        def endpointUri = "direct:a2"

        def receive = {
          case "throw"         ⇒ throw new TestException("")
          case m: CamelMessage ⇒ sender ! "received " + m.bodyAs[String]
        }

        override def postRestart(reason: Throwable) {
          restarted.countDown()
        }
      }, "direct-a2")
      filterEvents(EventFilter[TestException](occurrences = 1)) {
        consumer ! "throw"
        Await.ready(restarted, defaultTimeout)

        camel.sendTo("direct:a2", msg = "xyz") must be("received xyz")
      }
      stop(consumer)
    }

    "Consumer must unregister itself when stopped" in {
      val consumer = start(new TestActor(), name = "test-actor-unregister")
      Await.result(camel.activationFutureFor(consumer), defaultTimeout)

      camel.routeCount must be > (0)

      system.stop(consumer)
      Await.result(camel.deactivationFutureFor(consumer), defaultTimeout)

      camel.routeCount must be(0)
    }

    "Consumer must register on uri passed in through constructor" in {
      val consumer = start(new TestActor("direct://test"), name = "direct-test")
      Await.result(camel.activationFutureFor(consumer), defaultTimeout)

      camel.routeCount must be > (0)
      camel.routes.get(0).getEndpoint.getEndpointUri must be("direct://test")
      system.stop(consumer)
      Await.result(camel.deactivationFutureFor(consumer), defaultTimeout)
      camel.routeCount must be(0)
      stop(consumer)
    }

    "Error passing consumer supports error handling through route modification" in {
      val ref = start(new ErrorThrowingConsumer("direct:error-handler-test") with ErrorPassing {
        override def onRouteDefinition(rd: RouteDefinition) = {
          rd.onException(classOf[TestException]).handled(true).transform(Builder.exceptionMessage).end
        }
      }, name = "direct-error-handler-test")
      filterEvents(EventFilter[TestException](occurrences = 1)) {
        camel.sendTo("direct:error-handler-test", msg = "hello") must be("error: hello")
      }
      stop(ref)
    }

    "Error passing consumer supports redelivery through route modification" in {
      val ref = start(new FailingOnceConsumer("direct:failing-once-concumer") with ErrorPassing {
        override def onRouteDefinition(rd: RouteDefinition) = {
          rd.onException(classOf[TestException]).maximumRedeliveries(1).end
        }
      }, name = "direct-failing-once-consumer")
      filterEvents(EventFilter[TestException](occurrences = 1)) {
        camel.sendTo("direct:failing-once-concumer", msg = "hello") must be("accepted: hello")
      }
      stop(ref)
    }

    "Consumer supports manual Ack" in {
      val ref = start(new ManualAckConsumer() {
        def endpointUri = "direct:manual-ack"
        def receive = { case _ ⇒ sender ! Ack }
      }, name = "direct-manual-ack-1")
      camel.template.asyncSendBody("direct:manual-ack", "some message").get(defaultTimeout.toSeconds, TimeUnit.SECONDS) must be(null) //should not timeout
      stop(ref)
    }

    "Consumer handles manual Ack failure" in {
      val someException = new Exception("e1")
      val ref = start(new ManualAckConsumer() {
        def endpointUri = "direct:manual-ack"
        def receive = { case _ ⇒ sender ! Failure(someException) }
      }, name = "direct-manual-ack-2")

      intercept[ExecutionException] {
        camel.template.asyncSendBody("direct:manual-ack", "some message").get(defaultTimeout.toSeconds, TimeUnit.SECONDS)
      }.getCause.getCause must be(someException)
      stop(ref)
    }

    "Consumer should time-out, if manual Ack not received within replyTimeout and should give a human readable error message" in {
      val ref = start(new ManualAckConsumer() {
        override def replyTimeout = 10 millis
        def endpointUri = "direct:manual-ack"
        def receive = { case _ ⇒ }
      }, name = "direct-manual-ack-3")

      intercept[ExecutionException] {
        camel.template.asyncSendBody("direct:manual-ack", "some message").get(defaultTimeout.toSeconds, TimeUnit.SECONDS)
      }.getCause.getCause.getMessage must include("Failed to get Ack")
      stop(ref)
    }
  }
}

class ErrorThrowingConsumer(override val endpointUri: String) extends Consumer {
  def receive = {
    case msg: CamelMessage ⇒ throw new TestException("error: %s" format msg.body)
  }
}

class FailingOnceConsumer(override val endpointUri: String) extends Consumer {

  def receive = {
    case msg: CamelMessage ⇒
      if (msg.headerAs[Boolean]("CamelRedelivered").getOrElse(false))
        sender ! ("accepted: %s" format msg.body)
      else
        throw new TestException("rejected: %s" format msg.body)
  }
}

class TestActor(uri: String = "file://target/abcde") extends Consumer {
  def endpointUri = uri
  def receive = { case _ ⇒ /* do nothing */ }
}

trait ErrorPassing {
  self: Actor ⇒
  final override def preRestart(reason: Throwable, message: Option[Any]) {
    sender ! Failure(reason)
  }
}

trait ManualAckConsumer extends Consumer {
  override def autoAck = false
}

class TestException(msg: String) extends Exception(msg)
