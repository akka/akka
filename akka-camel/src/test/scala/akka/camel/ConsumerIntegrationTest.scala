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
import org.apache.camel.{ FailedToCreateRouteException, CamelExecutionException }
import java.util.concurrent.{ ExecutionException, TimeUnit, TimeoutException }
import akka.testkit.TestLatch
import akka.actor.Status.Failure
import scala.concurrent.Await
import scala.concurrent.util.duration._

class ConsumerIntegrationTest extends WordSpec with MustMatchers with NonSharedCamelSystem {
  "ConsumerIntegrationTest" must {
    implicit val defaultTimeout = 10.seconds

    "Consumer must throw FailedToCreateRouteException, while awaiting activation, if endpoint is invalid" in {
      val actorRef = system.actorOf(Props(new TestActor(uri = "some invalid uri")))
      intercept[FailedToCreateRouteException] { Await.result(camel.activationFutureFor(actorRef), defaultTimeout) }
    }

    "Consumer must support in-out messaging" in {
      start(new Consumer {
        def endpointUri = "direct:a1"
        def receive = {
          case m: CamelMessage ⇒ sender ! "received " + m.bodyAs[String]
        }
      })
      camel.sendTo("direct:a1", msg = "some message") must be("received some message")
    }

    "Consumer must time-out if consumer is slow" in {
      val SHORT_TIMEOUT = 10 millis
      val LONG_WAIT = 200 millis

      start(new Consumer {
        override def replyTimeout = SHORT_TIMEOUT

        def endpointUri = "direct:a3"
        def receive = { case _ ⇒ { Thread.sleep(LONG_WAIT.toMillis); sender ! "done" } }
      })

      val exception = intercept[CamelExecutionException] {
        camel.sendTo("direct:a3", msg = "some msg 3")
      }
      exception.getCause.getClass must be(classOf[TimeoutException])
    }

    "Consumer must process messages even after actor restart" in {
      val restarted = TestLatch()
      val consumer = start(new Consumer {
        def endpointUri = "direct:a2"

        def receive = {
          case "throw"         ⇒ throw new Exception
          case m: CamelMessage ⇒ sender ! "received " + m.bodyAs[String]
        }

        override def postRestart(reason: Throwable) {
          restarted.countDown()
        }
      })
      consumer ! "throw"
      Await.ready(restarted, defaultTimeout)

      val response = camel.sendTo("direct:a2", msg = "xyz")
      response must be("received xyz")
    }

    "Consumer must unregister itself when stopped" in {
      val consumer = start(new TestActor())
      Await.result(camel.activationFutureFor(consumer), defaultTimeout)

      camel.routeCount must be > (0)

      system.stop(consumer)
      Await.result(camel.deactivationFutureFor(consumer), defaultTimeout)

      camel.routeCount must be(0)
    }

    "Consumer must register on uri passed in through constructor" in {
      val consumer = start(new TestActor("direct://test"))
      Await.result(camel.activationFutureFor(consumer), defaultTimeout)

      camel.routeCount must be > (0)
      camel.routes.get(0).getEndpoint.getEndpointUri must be("direct://test")
      system.stop(consumer)
      Await.result(camel.deactivationFutureFor(consumer), defaultTimeout)
      camel.routeCount must be(0)
    }

    "Error passing consumer supports error handling through route modification" in {
      start(new ErrorThrowingConsumer("direct:error-handler-test") with ErrorPassing {
        override def onRouteDefinition(rd: RouteDefinition) = {
          rd.onException(classOf[Exception]).handled(true).transform(Builder.exceptionMessage).end
        }
      })
      camel.sendTo("direct:error-handler-test", msg = "hello") must be("error: hello")
    }

    "Error passing consumer supports redelivery through route modification" in {
      start(new FailingOnceConsumer("direct:failing-once-concumer") with ErrorPassing {
        override def onRouteDefinition(rd: RouteDefinition) = {
          rd.onException(classOf[Exception]).maximumRedeliveries(1).end
        }
      })
      camel.sendTo("direct:failing-once-concumer", msg = "hello") must be("accepted: hello")
    }

    "Consumer supports manual Ack" in {
      start(new ManualAckConsumer() {
        def endpointUri = "direct:manual-ack"
        def receive = { case _ ⇒ sender ! Ack }
      })
      camel.template.asyncSendBody("direct:manual-ack", "some message").get(defaultTimeout.toSeconds, TimeUnit.SECONDS) must be(null) //should not timeout
    }

    "Consumer handles manual Ack failure" in {
      val someException = new Exception("e1")
      start(new ManualAckConsumer() {
        def endpointUri = "direct:manual-ack"
        def receive = { case _ ⇒ sender ! Failure(someException) }
      })

      intercept[ExecutionException] {
        camel.template.asyncSendBody("direct:manual-ack", "some message").get(defaultTimeout.toSeconds, TimeUnit.SECONDS)
      }.getCause.getCause must be(someException)
    }

    "Consumer should time-out, if manual Ack not received within replyTimeout and should give a human readable error message" in {
      start(new ManualAckConsumer() {
        override def replyTimeout = 10 millis
        def endpointUri = "direct:manual-ack"
        def receive = { case _ ⇒ }
      })

      intercept[ExecutionException] {
        camel.template.asyncSendBody("direct:manual-ack", "some message").get(defaultTimeout.toSeconds, TimeUnit.SECONDS)
      }.getCause.getCause.getMessage must include("Failed to get Ack")
    }
  }
}

class ErrorThrowingConsumer(override val endpointUri: String) extends Consumer {
  def receive = {
    case msg: CamelMessage ⇒ throw new Exception("error: %s" format msg.body)
  }
}

class FailingOnceConsumer(override val endpointUri: String) extends Consumer {

  def receive = {
    case msg: CamelMessage ⇒
      if (msg.headerAs[Boolean]("CamelRedelivered").getOrElse(false))
        sender ! ("accepted: %s" format msg.body)
      else
        throw new Exception("rejected: %s" format msg.body)
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
