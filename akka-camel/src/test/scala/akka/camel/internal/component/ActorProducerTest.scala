/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel.internal.component

import org.scalatest.mock.MockitoSugar
import org.mockito.Matchers.{ eq ⇒ the, any }
import org.mockito.Mockito._
import org.apache.camel.AsyncCallback
import java.util.concurrent.atomic.AtomicBoolean
import akka.util.duration._
import akka.util.Duration
import akka.testkit.{ TestKit, TestProbe }
import java.lang.String
import akka.actor.{ ActorRef, Props, ActorSystem, Actor }
import akka.camel._
import internal.CamelExchangeAdapter
import org.scalatest.{ Suite, WordSpec, BeforeAndAfterAll, BeforeAndAfterEach }
import akka.camel.TestSupport._
import java.util.concurrent.{ TimeoutException, CountDownLatch, TimeUnit }
import org.mockito.{ ArgumentMatcher, Matchers, Mockito }
import org.scalatest.matchers.MustMatchers
import akka.actor.Status.Failure

class ActorProducerTest extends TestKit(ActorSystem("test")) with WordSpec with MustMatchers with ActorProducerFixture {

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

        "pass the message to the consumer" in {
          producer.processExchangeAdapter(exchange)
          within(1 second)(probe.expectMsg(message))
        }

        "not expect response and not block" in {
          time(producer.processExchangeAdapter(exchange)) must be < (200 millis)
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

          def process() = {
            producer = given(outCapable = true, replyTimeout = 100 millis)
            time(producer.processExchangeAdapter(exchange))
          }

          "timeout after replyTimeout" in {
            val duration = process()
            duration must (be >= (100 millis) and be < (300 millis))
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

      //TODO: write more tests for synchronous process(exchange) method

    }

    "asynchronous" when {

      def verifyFailureIsSet {
        producer.processExchangeAdapter(exchange, asyncCallback)
        asyncCallback.awaitCalled()
        verify(exchange).setFailure(any[FailureResult])
      }

      "out-capable" when {

        "consumer actor doesnt exist" must {
          "set failure message on exchange" in {
            producer = given(actor = null, outCapable = true)
            verifyFailureIsSet
          }
        }

        "response is ok" must {
          "get a response and async callback as soon as it gets the response (but not before)" in {
            producer = given(outCapable = true)

            val doneSync = producer.processExchangeAdapter(exchange, asyncCallback)

            asyncCallback.expectNoCallWithin(100 millis); info("no async callback before response")

            within(1 second) {
              probe.expectMsgType[CamelMessage]
              probe.sender ! "some message"
            }
            doneSync must be(false); info("done async")

            asyncCallback.expectDoneAsyncWithin(1 second); info("async callback received")
            verify(exchange).setResponse(msg("some message")); info("response as expected")
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
              probe.sender ! failure
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
            verify(exchange).setFailure(Matchers.argThat(new ArgumentMatcher[FailureResult] {
              def matches(failure: AnyRef) = { failure.asInstanceOf[FailureResult].cause must be(anInstanceOf[TimeoutException]); true }

            }))
          }
        }

      }

      "in-only" when {

        "consumer actor doesnt exist" must {
          "set failure message on exchange" in {
            producer = given(actor = null, outCapable = false)
            verifyFailureIsSet
          }
        }

        "autoAck" must {

          "get sync callback as soon as it sends a message" in {

            producer = given(outCapable = false, autoAck = true)
            val doneSync = producer.processExchangeAdapter(exchange, asyncCallback)

            doneSync must be(true); info("done sync")
            asyncCallback.expectDoneSyncWithin(1 second); info("async callback called")
            verify(exchange, never()).setResponse(any[CamelMessage]); info("no response forwarded to exchange")
          }

        }

        "manualAck" when {

          "response is Ack" must {
            "get async callback" in {
              producer = given(outCapable = false, autoAck = false)

              val doneSync = producer.processExchangeAdapter(exchange, asyncCallback)

              doneSync must be(false)
              within(1 second) {
                probe.expectMsgType[CamelMessage]; info("message sent to consumer")
                probe.sender ! Ack
                asyncCallback.expectDoneAsyncWithin(remaining); info("async callback called")
              }
              verify(exchange, never()).setResponse(any[CamelMessage]); info("no response forwarded to exchange")
            }
          }

          "expecting Ack or Failure message and some other message is sent as a response" must {
            "fail" in {
              producer = given(outCapable = false, autoAck = false)

              producer.processExchangeAdapter(exchange, asyncCallback)

              within(1 second) {
                probe.expectMsgType[CamelMessage]; info("message sent to consumer")
                probe.sender ! "some neither Ack nor Failure response"
                asyncCallback.expectDoneAsyncWithin(remaining); info("async callback called")
              }
              verify(exchange, never()).setResponse(any[CamelMessage]); info("no response forwarded to exchange")
              verify(exchange).setFailure(any[FailureResult]); info("failure set")

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

              doneSync must be(false)
              within(1 second) {
                probe.expectMsgType[CamelMessage]; info("message sent to consumer")
                probe.sender ! Failure(new Exception)
                asyncCallback.awaitCalled(remaining);
              }
              verify(exchange, never()).setResponse(any[CamelMessage]); info("no response forwarded to exchange")
              verify(exchange).setFailure(any[FailureResult]); info("failure set")
            }
          }
        }
      }
    }
  }
}

trait ActorProducerFixture extends MockitoSugar with BeforeAndAfterAll with BeforeAndAfterEach { self: TestKit with MustMatchers with Suite ⇒
  var camel: Camel = _
  var exchange: CamelExchangeAdapter = _
  var callback: AsyncCallback = _

  var producer: ActorProducer = _
  var message: CamelMessage = _
  var probe: TestProbe = _
  var asyncCallback: TestAsyncCallback = _
  var actorEndpointPath: ActorEndpointPath = _
  var actorComponent: ActorComponent = _

  override protected def beforeEach() {
    asyncCallback = createAsyncCallback

    probe = TestProbe()
    camel = mock[Camel]
    exchange = mock[CamelExchangeAdapter]
    callback = mock[AsyncCallback]
    actorEndpointPath = mock[ActorEndpointPath]
    actorComponent = mock[ActorComponent]
    producer = new ActorProducer(config(), camel)
    message = CamelMessage(null, null)
  }

  override protected def afterAll() {
    system.shutdown()
  }

  def msg(s: String) = CamelMessage(s, Map.empty)

  def given(actor: ActorRef = probe.ref, outCapable: Boolean = true, autoAck: Boolean = true, replyTimeout: Duration = Int.MaxValue seconds) = {
    prepareMocks(actor, outCapable = outCapable)
    new ActorProducer(config(isAutoAck = autoAck, _replyTimeout = replyTimeout), camel)
  }

  def createAsyncCallback = new TestAsyncCallback

  class TestAsyncCallback extends AsyncCallback {
    def expectNoCallWithin(duration: Duration) {
      if (callbackReceived.await(duration.toNanos, TimeUnit.NANOSECONDS)) fail("NOT expected callback, but received one!")
    }

    def awaitCalled(timeout: Duration = 1 second) { valueWithin(1 second) }

    val callbackReceived = new CountDownLatch(1)
    val callbackValue = new AtomicBoolean()

    def done(doneSync: Boolean) {
      callbackValue set doneSync
      callbackReceived.countDown()
    }

    private[this] def valueWithin(implicit timeout: Duration) = {
      if (!callbackReceived.await(timeout.toNanos, TimeUnit.NANOSECONDS)) fail("Callback not received!")
      callbackValue.get
    }

    def expectDoneSyncWithin(implicit timeout: Duration) {
      if (!valueWithin(timeout)) fail("Expected to be done Synchronously")
    }
    def expectDoneAsyncWithin(implicit timeout: Duration) {
      if (valueWithin(timeout)) fail("Expected to be done Asynchronously")
    }

  }

  def config(endpointUri: String = "test-uri", isAutoAck: Boolean = true, _replyTimeout: Duration = Int.MaxValue seconds) = {
    val endpoint = new ActorEndpoint(endpointUri, actorComponent, actorEndpointPath, camel)
    endpoint.autoack = isAutoAck
    endpoint.replyTimeout = _replyTimeout
    endpoint
  }

  def prepareMocks(actor: ActorRef, message: CamelMessage = message, outCapable: Boolean) {
    when(actorEndpointPath.findActorIn(any[ActorSystem])) thenReturn Option(actor)
    when(exchange.toRequestMessage(any[Map[String, Any]])) thenReturn message
    when(exchange.isOutCapable) thenReturn outCapable
  }

  def echoActor = system.actorOf(Props(new Actor {
    def receive = {
      case msg ⇒ sender ! "received " + msg
    }
  }))

}
