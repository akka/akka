/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.camel

import language.postfixOps

import akka.actor._
import org.scalatest.Matchers
import org.scalatest.WordSpec
import akka.camel.TestSupport._
import org.apache.camel.model.{ RouteDefinition }
import org.apache.camel.builder.Builder
import org.apache.camel.{ CamelExecutionException, FailedToCreateRouteException }
import java.util.concurrent.{ ExecutionException, TimeUnit, TimeoutException }
import akka.actor.Status.Failure
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext }
import akka.testkit._
import akka.util.Timeout

class ConsumerIntegrationTest extends WordSpec with Matchers with NonSharedCamelSystem {
  "ConsumerIntegrationTest" must {
    val defaultTimeoutDuration = 10 seconds
    implicit val defaultTimeout = Timeout(defaultTimeoutDuration)
    implicit def ec: ExecutionContext = system.dispatcher

    "Consumer must throw FailedToCreateRouteException, while awaiting activation, if endpoint is invalid" in {
      filterEvents(EventFilter[FailedToCreateRouteException](pattern = "failed to activate.*", occurrences = 1)) {
        val actorRef = system.actorOf(Props(new TestActor(uri = "some invalid uri")), "invalidActor")
        intercept[FailedToCreateRouteException] {
          Await.result(camel.activationFutureFor(actorRef), defaultTimeoutDuration)
        }
      }
    }

    "Consumer must support in-out messaging" in {
      start(new Consumer {
        def endpointUri = "direct:a1"
        def receive = {
          case m: CamelMessage => sender() ! "received " + m.bodyAs[String]
        }
      }, name = "direct-a1")
      camel.sendTo("direct:a1", msg = "some message") should ===("received some message")
    }

    "Consumer must time-out if consumer is slow" taggedAs TimingTest in {
      val SHORT_TIMEOUT = 10 millis
      val LONG_WAIT = 1 second

      val ref = start(new Consumer {
        override def replyTimeout = SHORT_TIMEOUT
        def endpointUri = "direct:a3"
        def receive = { case _ => { Thread.sleep(LONG_WAIT.toMillis); sender() ! "done" } }
      }, name = "ignore-this-deadletter-timeout-consumer-reply")

      intercept[CamelExecutionException] {
        camel.sendTo("direct:a3", msg = "some msg 3")
      }.getCause.getClass should ===(classOf[TimeoutException])

      stop(ref)
    }

    "Consumer must process messages even after actor restart" in {
      val restarted = TestLatch()
      val consumer = start(new Consumer {
        def endpointUri = "direct:a2"

        def receive = {
          case "throw"         => throw new TestException("")
          case m: CamelMessage => sender() ! "received " + m.bodyAs[String]
        }

        override def postRestart(reason: Throwable): Unit = {
          restarted.countDown()
        }
      }, "direct-a2")
      filterEvents(EventFilter[TestException](occurrences = 1)) {
        consumer ! "throw"
        Await.ready(restarted, defaultTimeoutDuration)

        camel.sendTo("direct:a2", msg = "xyz") should ===("received xyz")
      }
      stop(consumer)
    }

    "Consumer must unregister itself when stopped" in {
      val consumer = start(new TestActor(), name = "test-actor-unregister")
      Await.result(camel.activationFutureFor(consumer), defaultTimeoutDuration)

      camel.routeCount should be > (0)

      system.stop(consumer)
      Await.result(camel.deactivationFutureFor(consumer), defaultTimeoutDuration)

      camel.routeCount should ===(0)
    }

    "Consumer must register on uri passed in through constructor" in {
      val consumer = start(new TestActor("direct://test"), name = "direct-test")
      Await.result(camel.activationFutureFor(consumer), defaultTimeoutDuration)

      camel.routeCount should be > (0)
      camel.routes.get(0).getEndpoint.getEndpointUri should ===("direct://test")
      system.stop(consumer)
      Await.result(camel.deactivationFutureFor(consumer), defaultTimeoutDuration)
      camel.routeCount should ===(0)
      stop(consumer)
    }

    "Error passing consumer supports error handling through route modification" in {
      val ref = start(new ErrorThrowingConsumer("direct:error-handler-test") {
        override def onRouteDefinition = (rd: RouteDefinition) => {
          rd.onException(classOf[TestException]).handled(true).transform(Builder.exceptionMessage).end
        }
      }, name = "direct-error-handler-test")
      filterEvents(EventFilter[TestException](occurrences = 1)) {
        camel.sendTo("direct:error-handler-test", msg = "hello") should ===("error: hello")
      }
      stop(ref)
    }

    "Error passing consumer supports redelivery through route modification" in {
      val ref = start(new FailingOnceConsumer("direct:failing-once-consumer") {
        override def onRouteDefinition = (rd: RouteDefinition) => {
          rd.onException(classOf[TestException]).redeliveryDelay(0L).maximumRedeliveries(1).end
        }
      }, name = "direct-failing-once-consumer")
      filterEvents(EventFilter[TestException](occurrences = 1)) {
        camel.sendTo("direct:failing-once-consumer", msg = "hello") should ===("accepted: hello")
      }
      stop(ref)
    }

    "Consumer supports manual Ack" in {
      val ref = start(new ManualAckConsumer() {
        def endpointUri = "direct:manual-ack"
        def receive = { case _ => sender() ! Ack }
      }, name = "direct-manual-ack-1")
      camel.template
        .asyncSendBody("direct:manual-ack", "some message")
        .get(defaultTimeoutDuration.toSeconds, TimeUnit.SECONDS) should ===(null) //should not timeout
      stop(ref)
    }

    "Consumer handles manual Ack failure" in {
      val someException = new Exception("e1")
      val ref = start(new ManualAckConsumer() {
        def endpointUri = "direct:manual-ack"
        def receive = { case _ => sender() ! Failure(someException) }
      }, name = "direct-manual-ack-2")

      intercept[ExecutionException] {
        camel.template
          .asyncSendBody("direct:manual-ack", "some message")
          .get(defaultTimeoutDuration.toSeconds, TimeUnit.SECONDS)
      }.getCause.getCause should ===(someException)
      stop(ref)
    }

    "Consumer should time-out, if manual Ack not received within replyTimeout and should give a human readable error message" in {
      val ref = start(new ManualAckConsumer() {
        override def replyTimeout = 10 millis
        def endpointUri = "direct:manual-ack"
        def receive = { case _ => }
      }, name = "direct-manual-ack-3")

      intercept[ExecutionException] {
        camel.template
          .asyncSendBody("direct:manual-ack", "some message")
          .get(defaultTimeoutDuration.toSeconds, TimeUnit.SECONDS)
      }.getCause.getCause.getMessage should include("Failed to get Ack")
      stop(ref)
    }
    "respond to onRouteDefinition" in {
      val ref = start(new ErrorRespondingConsumer("direct:error-responding-consumer-1"), "error-responding-consumer")
      filterEvents(EventFilter[TestException](occurrences = 1)) {
        val response = camel.sendTo("direct:error-responding-consumer-1", "some body")
        response should ===("some body has an error")
      }
      stop(ref)
    }
  }
}

class ErrorThrowingConsumer(override val endpointUri: String) extends Consumer {
  def receive = {
    case msg: CamelMessage => throw new TestException("error: %s".format(msg.body))
  }
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    super.preRestart(reason, message)
    sender() ! Failure(reason)
  }
}

class ErrorRespondingConsumer(override val endpointUri: String) extends Consumer {
  def receive = {
    case msg: CamelMessage => throw new TestException("Error!")
  }
  override def onRouteDefinition = (rd: RouteDefinition) => {
    // Catch TestException and handle it by returning a modified version of the in message
    rd.onException(classOf[TestException]).handled(true).transform(Builder.body.append(" has an error")).end
  }

  final override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    super.preRestart(reason, message)
    sender() ! Failure(reason)
  }
}

class FailingOnceConsumer(override val endpointUri: String) extends Consumer {

  def receive = {
    case msg: CamelMessage =>
      if (msg.headerAs[Boolean]("CamelRedelivered").getOrElse(false))
        sender() ! ("accepted: %s".format(msg.body))
      else
        throw new TestException("rejected: %s".format(msg.body))
  }

  final override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    super.preRestart(reason, message)
    sender() ! Failure(reason)
  }
}

class TestActor(uri: String = "file://target/abcde") extends Consumer {
  def endpointUri = uri
  def receive = { case _ => /* do nothing */ }
}

trait ManualAckConsumer extends Consumer {
  override def autoAck = false
}

class TestException(msg: String) extends Exception(msg)
