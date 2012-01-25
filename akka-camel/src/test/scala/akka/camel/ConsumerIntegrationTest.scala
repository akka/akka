/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import akka.actor._
import org.scalatest.matchers.MustMatchers
import org.scalatest.mock.MockitoSugar
import org.mockito.Matchers.{ eq ⇒ the }
import akka.util.duration._
import java.util.concurrent.TimeUnit._
import org.apache.camel.{ FailedToCreateRouteException, CamelExecutionException }
import TestSupport._
import java.util.concurrent.{ TimeoutException, CountDownLatch }
import org.scalatest.{WordSpec, BeforeAndAfterEach, FlatSpec}

class ConsumerIntegrationTest extends WordSpec with MustMatchers with MockitoSugar with BeforeAndAfterEach {
  implicit var system: ActorSystem = _

  override protected def beforeEach() {
    system = ActorSystem("test")
  }

  override protected def afterEach() {
    system.shutdown()
  }

  class TestActor(uri: String = "file://target/abcde") extends Actor with Consumer {
    def endpointUri = uri
    protected def receive = { case _ ⇒ println("foooo..") }
  }

  //TODO test manualAck

  //TODO: decide on Camel lifecycle. Ideally it must prevent creating non-started instances, so there is no need to test if consumers fail when Camel is not initialized.
  "Consumer must fail if camel is not started" in (pending)

  "Consumer must throw FailedToCreateRouteException, while awaiting activation, if endpoint is invalid" in {
    val actorRef = system.actorOf(Props(new TestActor(uri = "some invalid uri")))

    intercept[FailedToCreateRouteException] {
      CamelExtension(system).awaitActivation(actorRef, 1 second)
    }
  }

  def camel: Camel = {
    CamelExtension(system)
  }

  "Consumer must support in-out messaging" in {
    start(new Consumer {
      def endpointUri = "direct:a1"
      protected def receive = {
        case m: Message ⇒ sender ! "received " + m.bodyAs[String]
      }
    })
    camel.sendTo("direct:a1", msg = "some message") must be("received some message")
  }

  "Consumer must support blocking, in-out messaging" in {
    start(new Consumer {
      def endpointUri = "direct:a1"
      override def communicationStyle = Blocking(200 millis)

      protected def receive = {
        case m: Message ⇒ {
          Thread.sleep(150)
          sender ! "received " + m.bodyAs[String]
        }
      }
    })
    time(camel.sendTo("direct:a1", msg = "some message")) must be >= (150 millis)
  }

  "Consumer must time-out if consumer is slow" in {
    val SHORT_TIMEOUT = 10 millis
    val LONG_WAIT = 200 millis

    start(new Consumer {
      override def replyTimeout = SHORT_TIMEOUT

      def endpointUri = "direct:a3"
      protected def receive = { case _ ⇒ { Thread.sleep(LONG_WAIT.toMillis); sender ! "done" } }
    })

    val exception = intercept[CamelExecutionException] {
      camel.sendTo("direct:a3", msg = "some msg 3")
    }
    exception.getCause.getClass must be(classOf[TimeoutException])
  }

  "Consumer must process messages even after actor restart" in {
    val restarted = new CountDownLatch(1)
    val consumer = start(new Consumer {
      def endpointUri = "direct:a2"

      protected def receive = {
        case "throw"    ⇒ throw new Exception
        case m: Message ⇒ sender ! "received " + m.bodyAs[String]
      }

      override def postRestart(reason: Throwable) {
        println("RESTARTED!!!")
        restarted.countDown()
      }
    })
    consumer ! "throw"
    if (!restarted.await(5, SECONDS)) fail("Actor failed to restart!")

    val response = camel.sendTo("direct:a2", msg = "xyz")
    response must be("received xyz")
  }

  "Consumer must unregister itself when stopped" in {
    val actorRef = start(new TestActor())
    camel.awaitActivation(actorRef, 1 second)

    camel.routeCount must be > (0)

    system.stop(actorRef)
    camel.awaitDeactivation(actorRef, 1 second)

    camel.routeCount must be(0)
  }

}