package akka.camel.internal.component

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.mock.MockitoSugar
import org.mockito.Matchers.{eq => the, any}
import org.mockito.Mockito._
import org.apache.camel.AsyncCallback
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import akka.util.duration._
import akka.util.Duration
import akka.testkit.{TestKit, TestProbe}
import java.lang.String
import akka.actor.{ActorRef, Props, ActorSystem, Actor}
import akka.camel._
import org.scalatest.{Suite, WordSpec, BeforeAndAfterAll, BeforeAndAfterEach}
import akka.camel.TestSupport._



class ActorProducerTest extends TestKit(ActorSystem("test")) with WordSpec with ShouldMatchers with ActorProducerFixture{


  "ActorProducer" when {

    "consumer actor doesnt exist" should {
      "set failure message on exchange" in (pending)
    }

    "synchronous" when {

      "in-only" should{
        def process() = {
          producer = given(outCapable = false)
          time(producer.process(exchange))
        }
        
        "pass the message to the consumer" in {
          process()
          within(1 second)(probe.expectMsg(message))
        }
        
        "not expect response and not block" in {
          process should be < (30 millis)
        }

      }

      "out capable" when{
        "response is sent back by actor" should{

          "get a response" in {
            producer = given(actor = echoActor, outCapable = true)

            producer.process(exchange)

            verify(exchange).setResponse(msg("received "+message))
          }
        }

        "response is not sent by actor" should{

          def process() = {
            producer = given(outCapable=true, outTimeout = 100 millis)
            time(producer.process(exchange))
          }

          "timeout after outTimeout" in {
            val duration = process()
            duration should (be >= (100 millis) and be < (200 millis))
          }

          "never set the response on exchange" in {
            process()
            verify(exchange, never()).setResponse(any[Message])
          }

          "set failure message to timeout" in {
            process()
            verify (exchange).setFailure(any[Failure])
          }
        }

      }

      //TODO: write more tests for synchronous process(exchange) method

    }

    "asynchronous" when{
      "out-capable" when{
        "response is Failure" should  {
          "set an exception on exchange" in {
            val failure = Failure(new RuntimeException("some failure"))

            producer = given(outCapable = true)

            producer.process(exchange, asyncCallback)

            within(1 second){
              probe.expectMsgType[Message]
              probe.sender ! failure
              asyncCallback.awaitCalled(remaining)
            }

            verify(exchange).setFailure(failure)
          }
        }

        "non blocking" should{
          "get a response and async callback as soon as it gets the response (but not before)" in {
            producer = given(outCapable = true, blocking = NonBlocking)

            val doneSync = producer.process(exchange, asyncCallback)

            asyncCallback.expectNoCallWithin(100 millis); info("no async callback before response")


            within(1 second){
              probe.expectMsgType[Message]
              probe.sender ! "some message"
            }
            doneSync should be (false); info("done async")

            asyncCallback.expectDoneAsyncWithin(1 second); info("async callback received")
            verify(exchange).setResponse(msg("some message")); info("response as expected")
          }

        }

        "blocking" when {
          "it gets an output message" should{

            "set a correct response and call sync callback" in {
              producer = given(actor = echoActor, outCapable = true, blocking = Blocking(1 second))

              val doneSync = producer.process(exchange, asyncCallback)


              doneSync should be (true); info("done sync")
              //TODO: This is a bit lame test. Happy for any suggestions.
              asyncCallback.expectDoneSyncWithin(0 second); info("callback called immediately")
              verify(exchange).setResponse(msg("received "+message)); info("response as expected")
            }
          }

          "it doesnt get output message" should{

            def process() = {
              producer = given( outCapable = true, blocking = Blocking(100 millis), autoAck = false)
              time(
                producer.process(exchange, asyncCallback)
              )
            }

            "timeout, roughly after time specified by blocking timeout parameter" in {
              process() should (be >= (100 millis) and be < (200 millis))
            }

            "call a callback" in {
              process()
              asyncCallback.expectDoneSyncWithin(0 second)

            }
            "set failure message" in {
              process()
              verify(exchange).setFailure(any[Failure])
            }
          }

        }

      }

      "in-only" when{
        "autoAck" should {
          "get async callback as soon as it sends a message" in {

            producer = given( outCapable = false, blocking = NonBlocking, autoAck = true)
            val doneSync = producer.process(exchange, asyncCallback)

            doneSync should be (false); info("done async")
            asyncCallback.expectDoneAsyncWithin(1 second); info("async callback called")
            verify(exchange, never()).setResponse(any[Message]); info("no response forwarded to exchange")
          }

          "disallow blocking" in {
            producer = given( outCapable = false, blocking = Blocking(1 second), autoAck = true)

            intercept[IllegalStateException]{
              producer.process(exchange, mock[AsyncCallback])
            }
          }



        }

        "manualAck" when{

          //TODO: write this test
          "expecting Ack or Failure message and some other message is sent as a response" should{
            "fail" in  pending
          }


          "doesnt get Ack within timeout" should {
            "set failure on exchange" in {
              producer = given( outCapable = false, blocking = Blocking(10 millis), autoAck = false)

              producer.process(exchange, asyncCallback)

              verify(exchange).setFailure(any[Failure])

            }
          }

          "non blocking" should{
            "get async callback as soon as it gets an Ack message" in {
              producer = given( outCapable = false, blocking = NonBlocking, autoAck = false)

              val doneSync = producer.process(exchange, asyncCallback)

              doneSync should be (false)
              within(1 second){
                probe.expectMsgType[Message]; info("message sent to consumer")
                probe.sender ! Ack
                asyncCallback.expectDoneAsyncWithin(remaining); info("async callback called")
              }
              verify(exchange, never()).setResponse(any[Message]); info("no response forwarded to exchange")
            }
          }

          "blocking" should{
            "get sync callback when it gets an Ack message" in {

              producer = given( outCapable = false, blocking = Blocking(1 second), autoAck = false)

              val doneSync = producer.process(exchange, asyncCallback)


              doneSync should be (true)
              within(5 millis){
                probe.expectMsgType[Message]; info("message already received by consumer")
                probe.sender ! Ack
              }
              asyncCallback.expectDoneSyncWithin(1 second); info("sync callback received")
              verify(exchange, never()).setResponse(any[Message]); info("no response forwarded to exchange")
            }

          }

        }

      }

    }
  }

}

trait ActorProducerFixture extends MockitoSugar with BeforeAndAfterAll with BeforeAndAfterEach{ self:TestKit with ShouldMatchers with Suite=>
  var camel : Camel  = _
  var exchange : CamelExchangeAdapter = _
  var callback : AsyncCallback = _

  var producer : TestableProducer = _
  var message : Message = _
  var probe : TestProbe = _
  var asyncCallback : TestAsyncCallback = _



  override protected def beforeEach() {
    asyncCallback = createAsyncCallback

    probe = TestProbe()
    camel = mock[Camel]
    exchange = mock[CamelExchangeAdapter]
    callback = mock[AsyncCallback]

    producer = new TestableProducer(config(), camel)
    message = Message(null, null, null)
  }

  override protected def afterAll() {
    system.shutdown()
  }

  def msg(s: String) = Message(s, Map.empty, camel.context)


  def given(actor: ActorRef = probe.ref, outCapable: Boolean, blocking: BlockingOrNot = NonBlocking, autoAck: Boolean = true, outTimeout : Duration = Int.MaxValue seconds) = {
    prepareMocks(actor, outCapable = outCapable)
    new TestableProducer(config(isBlocking = blocking, isAutoAck = autoAck, _outTimeout = outTimeout), camel)
  }

  def createAsyncCallback = new TestAsyncCallback

  class TestAsyncCallback extends AsyncCallback{
    def expectNoCallWithin(duration: Duration){
      if (callbackReceived.await(duration.toNanos, TimeUnit.NANOSECONDS )) fail("NOT expected callback, but received one!")
    }

    def awaitCalled(timeout: Duration = 1 second){ valueWithin(1 second)}

    val callbackReceived = new CountDownLatch(1)
    val callbackValue = new AtomicBoolean()

    def done(doneSync: Boolean) {
      callbackValue set doneSync
      callbackReceived.countDown()
    }

    private[this] def valueWithin(implicit timeout:Duration) ={
      if (! callbackReceived.await(timeout.toNanos, TimeUnit.NANOSECONDS)) fail("Callback not received!")
      callbackValue.get
    }

    def expectDoneSyncWithin(implicit timeout:Duration) {
      if (!valueWithin(timeout)) fail("Expected to be done Synchronously")
    }
    def expectDoneAsyncWithin(implicit timeout:Duration) {
      if (valueWithin(timeout)) fail("Expected to be done Asynchronously")
    }

  }

  def config(actorPath: String = "path:akka://test/path",  endpointUri: String = "test-uri",  isBlocking: BlockingOrNot = NonBlocking, isAutoAck : Boolean = true, _outTimeout : Duration = Int.MaxValue seconds) = {
    new ActorEndpointConfig {
      val path = ActorEndpointPath.fromCamelPath(actorPath)
      val getEndpointUri = endpointUri
      blocking = isBlocking
      autoack = isAutoAck
      outTimeout = _outTimeout
    }
  }

  def prepareMocks(actor: ActorRef, message: Message = message, outCapable: Boolean) {
    when(camel.findActor(any[ActorEndpointPath])) thenReturn Option(actor)
    when(exchange.toRequestMessage(any[Map[String, Any]])) thenReturn message
    when(exchange.isOutCapable) thenReturn outCapable
  }

  def echoActor = system.actorOf(Props(new Actor {
    protected def receive = {
      case msg => sender ! "received " + msg
    }
  }))

}
