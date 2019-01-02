/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.camel.internal.component

import language.postfixOps
import org.scalatest.mock.MockitoSugar
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.apache.camel.{ AsyncCallback, ProducerTemplate }
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.duration._
import akka.camel._
import internal.{ CamelExchangeAdapter, DefaultCamel }
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach, Suite, WordSpecLike }
import akka.camel.TestSupport._
import java.util.concurrent.{ CountDownLatch, TimeoutException }

import org.mockito.{ ArgumentMatcher, ArgumentMatchers, Mockito }
import org.scalatest.Matchers
import akka.actor.Status.Failure
import com.typesafe.config.ConfigFactory
import akka.actor.ActorSystem.Settings
import akka.event.MarkerLoggingAdapter
import akka.testkit.{ TestKit, TestLatch, TestProbe, TimingTest }
import org.apache.camel.impl.DefaultCamelContext

import scala.concurrent.{ Await, Future }
import akka.util.Timeout
import akka.actor._
import akka.testkit._

class ActorProducerTest extends TestKit(ActorSystem("ActorProducerTest")) with WordSpecLike with Matchers with ActorProducerFixture {
  implicit val timeout = Timeout(10 seconds)

  "ActorProducer" when {

    "synchronous" when {

      "consumer actor doesnt exist" must {
        "set failure message on exchange" in {
          producer = given(actor = null)
          producer.processExchangeAdapter(exchange)

          verify(exchange).setFailure(any[FailureResult])
        }
      }

      "in-only" must {
        def producer = given(outCapable = false)

        "pass the message to the consumer" taggedAs TimingTest in {
          producer.processExchangeAdapter(exchange)
          within(1 second)(probe.expectMsg(message))
        }

        "not expect response and not block" taggedAs TimingTest in {
          time(producer.processExchangeAdapter(exchange)) should be < (200 millis)
        }
      }

      "manualAck" when {

        "response is Ack" must {
          "process the exchange" in {
            producer = given(outCapable = false, autoAck = false)
            import system.dispatcher
            val future = Future { producer.processExchangeAdapter(exchange) }
            within(1 second) {
              probe.expectMsgType[CamelMessage]
              info("message sent to consumer")
              probe.sender() ! Ack
            }
            verify(exchange, never()).setResponse(any[CamelMessage])
            info("no response forwarded to exchange")
            Await.ready(future, timeout.duration)
          }
        }
        "the consumer does not respond wit Ack" must {
          "not block forever" in {
            producer = given(outCapable = false, autoAck = false)
            import system.dispatcher
            val future = Future {
              producer.processExchangeAdapter(exchange)
            }
            within(1 second) {
              probe.expectMsgType[CamelMessage]
              info("message sent to consumer")
            }
            verify(exchange, never()).setResponse(any[CamelMessage])
            info("no response forwarded to exchange")
            intercept[TimeoutException] {
              Await.ready(future, camel.settings.ReplyTimeout - (1 seconds))
            }
          }
        }
      }

      "out capable" when {
        "response is sent back by actor" must {

          "get a response" in {
            producer = given(actor = echoActor, outCapable = true)

            producer.processExchangeAdapter(exchange)

            verify(exchange).setResponse(msg("received " + message))
          }
        }

        "response is not sent by actor" must {
          val latch = TestLatch(1)
          val callback = new AsyncCallback {
            def done(doneSync: Boolean): Unit = {
              latch.countDown()
            }
          }
          def process() = {
            producer = given(outCapable = true, replyTimeout = 100 millis)
            val duration = time {
              producer.processExchangeAdapter(exchange, callback)
              // wait for the actor to complete the callback
              Await.ready(latch, 1.seconds.dilated)
            }
            latch.reset()
            duration
          }

          "timeout after replyTimeout" taggedAs TimingTest in {
            val duration = process()
            duration should (be >= (100 millis) and be < (2000 millis))
          }

          "never set the response on exchange" in {
            process()
            verify(exchange, Mockito.never()).setResponse(any[CamelMessage])
          }

          "set failure message to timeout" in {
            process()
            verify(exchange).setFailure(any[FailureResult])
          }
        }

      }
    }

    "asynchronous" when {

      def verifyFailureIsSet(): Unit = {
        producer.processExchangeAdapter(exchange, asyncCallback)
        asyncCallback.awaitCalled()
        verify(exchange).setFailure(any[FailureResult])
      }

      "out-capable" when {

        "consumer actor doesnt exist" must {
          "set failure message on exchange" in {
            producer = given(actor = null, outCapable = true)
            verifyFailureIsSet()
          }
        }

        "response is ok" must {
          "get a response and async callback as soon as it gets the response (but not before)" in {
            producer = given(outCapable = true)

            val doneSync = producer.processExchangeAdapter(exchange, asyncCallback)

            asyncCallback.expectNoCallWithin(100 millis)
            info("no async callback before response")

            within(1 second) {
              probe.expectMsgType[CamelMessage]
              probe.sender() ! "some message"
            }
            doneSync should ===(false)
            info("done async")

            asyncCallback.expectDoneAsyncWithin(1 second)
            info("async callback received")
            verify(exchange).setResponse(msg("some message"))
            info("response as expected")
          }
        }

        "response is Failure" must {
          "set an exception on exchange" in {
            val exception = new RuntimeException("some failure")
            val failure = Failure(exception)

            producer = given(outCapable = true)

            producer.processExchangeAdapter(exchange, asyncCallback)

            within(1 second) {
              probe.expectMsgType[CamelMessage]
              probe.sender() ! failure
              asyncCallback.awaitCalled(remaining)
            }

            verify(exchange).setFailure(FailureResult(exception))
          }
        }

        "no response is sent within timeout" must {
          "set TimeoutException on exchange" in {
            producer = given(outCapable = true, replyTimeout = 10 millis)
            producer.processExchangeAdapter(exchange, asyncCallback)
            asyncCallback.awaitCalled(100 millis)
            verify(exchange).setFailure(ArgumentMatchers.argThat(new ArgumentMatcher[FailureResult] {
              def matches(failure: FailureResult) = {
                failure.asInstanceOf[FailureResult].cause should be(anInstanceOf[TimeoutException])
                true
              }

            }))
          }
        }

      }

      "in-only" when {

        "consumer actor doesnt exist" must {
          "set failure message on exchange" in {
            producer = given(actor = null, outCapable = false)
            verifyFailureIsSet()
          }
        }

        "autoAck" must {

          "get sync callback as soon as it sends a message" in {

            producer = given(outCapable = false, autoAck = true)
            val doneSync = producer.processExchangeAdapter(exchange, asyncCallback)

            doneSync should ===(true)
            info("done sync")
            asyncCallback.expectDoneSyncWithin(1 second)
            info("async callback called")
            verify(exchange, never()).setResponse(any[CamelMessage])
            info("no response forwarded to exchange")
          }

        }

        "manualAck" when {

          "response is Ack" must {
            "get async callback" in {
              producer = given(outCapable = false, autoAck = false)

              val doneSync = producer.processExchangeAdapter(exchange, asyncCallback)

              doneSync should ===(false)
              within(1 second) {
                probe.expectMsgType[CamelMessage]
                info("message sent to consumer")
                probe.sender() ! Ack
                asyncCallback.expectDoneAsyncWithin(remaining)
                info("async callback called")
              }
              verify(exchange, never()).setResponse(any[CamelMessage])
              info("no response forwarded to exchange")
            }
          }

          "expecting Ack or Failure message and some other message is sent as a response" must {
            "fail" in {
              producer = given(outCapable = false, autoAck = false)

              producer.processExchangeAdapter(exchange, asyncCallback)

              within(1 second) {
                probe.expectMsgType[CamelMessage]
                info("message sent to consumer")
                probe.sender() ! "some neither Ack nor Failure response"
                asyncCallback.expectDoneAsyncWithin(remaining)
                info("async callback called")
              }
              verify(exchange, never()).setResponse(any[CamelMessage])
              info("no response forwarded to exchange")
              verify(exchange).setFailure(any[FailureResult])
              info("failure set")

            }
          }

          "no Ack is sent within timeout" must {
            "set failure on exchange" in {
              producer = given(outCapable = false, replyTimeout = 10 millis, autoAck = false)

              producer.processExchangeAdapter(exchange, asyncCallback)
              asyncCallback.awaitCalled(100 millis)
              verify(exchange).setFailure(any[FailureResult])

            }
          }

          "response is Failure" must {
            "set an exception on exchange" in {
              producer = given(outCapable = false, autoAck = false)

              val doneSync = producer.processExchangeAdapter(exchange, asyncCallback)

              doneSync should ===(false)
              within(1 second) {
                probe.expectMsgType[CamelMessage]
                info("message sent to consumer")
                probe.sender() ! Failure(new Exception)
                asyncCallback.awaitCalled(remaining)
              }
              verify(exchange, never()).setResponse(any[CamelMessage])
              info("no response forwarded to exchange")
              verify(exchange).setFailure(any[FailureResult])
              info("failure set")
            }
          }
        }
      }
    }
  }
}

private[camel] trait ActorProducerFixture extends MockitoSugar with BeforeAndAfterAll with BeforeAndAfterEach { self: TestKit with Matchers with Suite ⇒
  var camel: Camel = _
  var exchange: CamelExchangeAdapter = _
  var callback: AsyncCallback = _

  var producer: ActorProducer = _
  var message: CamelMessage = _
  var probe: TestProbe = _
  var asyncCallback: TestAsyncCallback = _
  var actorEndpointPath: ActorEndpointPath = _
  var actorComponent: ActorComponent = _

  override protected def beforeEach(): Unit = {
    asyncCallback = createAsyncCallback

    probe = TestProbe()

    val sys = mock[ExtendedActorSystem]
    val config = ConfigFactory.defaultReference()
    when(sys.dispatcher) thenReturn system.dispatcher
    when(sys.dynamicAccess) thenReturn system.asInstanceOf[ExtendedActorSystem].dynamicAccess
    when(sys.settings) thenReturn (new Settings(this.getClass.getClassLoader, config, "mocksystem"))
    when(sys.name) thenReturn ("mocksystem")

    def camelWithMocks = new DefaultCamel(sys) {
      override val log = mock[MarkerLoggingAdapter]
      override lazy val template = mock[ProducerTemplate]
      override lazy val context = mock[DefaultCamelContext]
      override val settings = new CamelSettings(ConfigFactory.parseString(
        """
          akka {
            camel {
              jmx = off
              streamingCache = on
              consumer {
                 auto-ack = on
                 reply-timeout = 2s
                 activation-timeout = 10s
              }
            }
          }
        """).withFallback(config), sys.dynamicAccess)
    }
    camel = camelWithMocks

    exchange = mock[CamelExchangeAdapter]
    callback = mock[AsyncCallback]
    actorEndpointPath = mock[ActorEndpointPath]
    actorComponent = mock[ActorComponent]
    producer = new ActorProducer(configure(), camel)
    message = CamelMessage(null, null)
  }

  override protected def afterAll(): Unit = {
    shutdown()
  }

  def msg(s: String) = CamelMessage(s, Map.empty)

  def given(actor: ActorRef = probe.ref, outCapable: Boolean = true, autoAck: Boolean = true, replyTimeout: FiniteDuration = 20 seconds) = {
    prepareMocks(actor, outCapable = outCapable)
    new ActorProducer(configure(isAutoAck = autoAck, _replyTimeout = replyTimeout), camel)
  }

  def createAsyncCallback = new TestAsyncCallback

  class TestAsyncCallback extends AsyncCallback {
    def expectNoCallWithin(duration: Duration): Unit =
      if (callbackReceived.await(duration.length, duration.unit)) fail("NOT expected callback, but received one!")
    def awaitCalled(timeout: Duration = 1 second): Unit = { valueWithin(1 second) }

    val callbackReceived = new CountDownLatch(1)
    val callbackValue = new AtomicBoolean()

    def done(doneSync: Boolean): Unit = {
      callbackValue set doneSync
      callbackReceived.countDown()
    }

    private[this] def valueWithin(implicit timeout: FiniteDuration) =
      if (!callbackReceived.await(timeout.length, timeout.unit)) fail("Callback not received!")
      else callbackValue.get

    def expectDoneSyncWithin(implicit timeout: FiniteDuration): Unit = if (!valueWithin(timeout)) fail("Expected to be done Synchronously")
    def expectDoneAsyncWithin(implicit timeout: FiniteDuration): Unit = if (valueWithin(timeout)) fail("Expected to be done Asynchronously")

  }

  def configure(endpointUri: String = "test-uri", isAutoAck: Boolean = true, _replyTimeout: FiniteDuration = 20 seconds) = {
    val endpoint = new ActorEndpoint(endpointUri, actorComponent, actorEndpointPath, camel)
    endpoint.autoAck = isAutoAck
    endpoint.replyTimeout = _replyTimeout
    endpoint
  }

  def prepareMocks(actor: ActorRef, message: CamelMessage = message, outCapable: Boolean): Unit = {
    when(actorEndpointPath.findActorIn(any[ActorSystem])) thenReturn Option(actor)
    when(exchange.toRequestMessage(any[Map[String, Any]])) thenReturn message
    when(exchange.isOutCapable) thenReturn outCapable
  }

  def echoActor = system.actorOf(Props(new Actor {
    def receive = { case msg ⇒ sender() ! "received " + msg }
  }), name = "echoActor")

}
