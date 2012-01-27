/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel.internal.component

import org.scalatest.matchers.MustMatchers
import org.scalatest.mock.MockitoSugar
import org.mockito.Matchers.{ eq ⇒ the, any }
import org.mockito.Mockito._
import org.apache.camel.AsyncCallback
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicBoolean
import akka.util.duration._
import akka.util.Duration
import akka.testkit.{ TestKit, TestProbe }
import java.lang.String
import akka.actor.{ ActorRef, Props, ActorSystem, Actor }
import akka.camel._
import org.scalatest.{ Suite, WordSpec, BeforeAndAfterAll, BeforeAndAfterEach }
import akka.camel.TestSupport._
import org.mockito.Mockito

class ActorProducerTest extends TestKit(ActorSystem("test")) with WordSpec with MustMatchers with ActorProducerFixture {

  "ActorProducer" when {

    "consumer actor doesnt exist" must {
      "set failure message on exchange" in (pending)
    }

    "synchronous" when {

      "in-only" must {
        def process() = {
          producer = given(outCapable = false)
          time(producer.process(exchange))
        }

        "pass the message to the consumer" in {
          process()
          within(1 second)(probe.expectMsg(message))
        }

        "not expect response and not block" in {
          process must be < (30 millis)
        }

      }

      "out capable" when {
        "response is sent back by actor" must {

          "get a response" in {
            producer = given(actor = echoActor, outCapable = true)

            producer.process(exchange)

            verify(exchange).setResponse(msg("received " + message))
          }
        }

        "response is not sent by actor" must {

          def process() = {
            producer = given(outCapable = true, replyTimeout = 100 millis)
            time(producer.process(exchange))
          }

          "timeout after replyTimeout" in {
            val duration = process()
            duration must (be >= (100 millis) and be < (200 millis))
          }

          "never set the response on exchange" in {
            process()
            verify(exchange, Mockito.never()).setResponse(any[Message])
          }

          "set failure message to timeout" in {
            process()
            verify(exchange).setFailure(any[Failure])
          }
        }

      }

      //TODO: write more tests for synchronous process(exchange) method

    }

    "asynchronous" when {
      "out-capable" when {

        "response is ok" must {
          "get a response and async callback as soon as it gets the response (but not before)" in {
            producer = given(outCapable = true)

            val doneSync = producer.process(exchange, asyncCallback)

            asyncCallback.expectNoCallWithin(100 millis); info("no async callback before response")

            within(1 second) {
              probe.expectMsgType[Message]
              probe.sender ! "some message"
            }
            doneSync must be(false); info("done async")

            asyncCallback.expectDoneAsyncWithin(1 second); info("async callback received")
            verify(exchange).setResponse(msg("some message")); info("response as expected")
          }
        }

        "response is Failure" must {
          "set an exception on exchange" in {
            val failure = Failure(new RuntimeException("some failure"))

            producer = given(outCapable = true)

            producer.process(exchange, asyncCallback)

            within(1 second) {
              probe.expectMsgType[Message]
              probe.sender ! failure
              asyncCallback.awaitCalled(remaining)
            }

            verify(exchange).setFailure(failure)
          }
        }

        "no response is sent within timeout" must {
          "set failure on exchange" in pending
        }

      }

      "in-only" when {
        "autoAck" must {

          "get sync callback as soon as it sends a message" in {

            producer = given(outCapable = false, autoAck = true)
            val doneSync = producer.process(exchange, asyncCallback)

            doneSync must be(true); info("done sync")
            asyncCallback.expectDoneSyncWithin(1 second); info("async callback called")
            verify(exchange, never()).setResponse(any[Message]); info("no response forwarded to exchange")
          }

        }

        "manualAck" when {

          "response is Ack" must {
            "get async callback" in {
              producer = given(outCapable = false, autoAck = false)

              val doneSync = producer.process(exchange, asyncCallback)

              doneSync must be(false)
              within(1 second) {
                probe.expectMsgType[Message]; info("message sent to consumer")
                probe.sender ! Ack
                asyncCallback.expectDoneAsyncWithin(remaining); info("async callback called")
              }
              verify(exchange, never()).setResponse(any[Message]); info("no response forwarded to exchange")
            }
          }

          //TODO: write this test
          "expecting Ack or Failure message and some other message is sent as a response" must {
            "fail" in pending
          }

          "no Ack is sent within timeout" must {
            "set failure on exchange" in {
              producer = given(outCapable = false, replyTimeout = 10 millis, autoAck = false)

              producer.process(exchange, asyncCallback)
              asyncCallback.awaitCalled(100 millis)
              verify(exchange).setFailure(any[Failure])

            }
          }

          "response is Failure" must {
            "set an exception on exchange" in pending
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

  var producer: ConsumerAsyncProcessor = _
  var message: Message = _
  var probe: TestProbe = _
  var asyncCallback: TestAsyncCallback = _
  var actorEndpointPath: ActorEndpointPath = _

  override protected def beforeEach() {
    asyncCallback = createAsyncCallback

    probe = TestProbe()
    camel = mock[Camel]
    exchange = mock[CamelExchangeAdapter]
    callback = mock[AsyncCallback]
    actorEndpointPath = mock[ActorEndpointPath]

    producer = new ConsumerAsyncProcessor(config(), camel)
    message = Message(null, null, null)
  }

  override protected def afterAll() {
    system.shutdown()
  }

  def msg(s: String) = Message(s, Map.empty, camel.context)

  def given(actor: ActorRef = probe.ref, outCapable: Boolean, autoAck: Boolean = true, replyTimeout: Duration = Int.MaxValue seconds) = {
    prepareMocks(actor, outCapable = outCapable)
    new ConsumerAsyncProcessor(config(isAutoAck = autoAck, _replyTimeout = replyTimeout), camel)
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
    new ActorEndpointConfig {
      val path = actorEndpointPath
      val getEndpointUri = endpointUri
      autoack = isAutoAck
      replyTimeout = _replyTimeout
    }
  }

  def prepareMocks(actor: ActorRef, message: Message = message, outCapable: Boolean) {
    when(actorEndpointPath.findActorIn(any[ActorSystem])) thenReturn Option(actor)
    when(exchange.toRequestMessage(any[Map[String, Any]])) thenReturn message
    when(exchange.isOutCapable) thenReturn outCapable
  }

  def echoActor = system.actorOf(Props(new Actor {
    protected def receive = {
      case msg ⇒ sender ! "received " + msg
    }
  }))

}
