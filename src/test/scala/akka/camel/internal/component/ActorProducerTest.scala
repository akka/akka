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
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpec}
import java.lang.String
import akka.actor.{ActorRef, Props, ActorSystem, Actor}
import akka.camel._

//TODO: this whole test doesn't seem right with FlatSpec, investigate other options, maybe given-when-then style
class ActorProducerTest extends TestKit(ActorSystem("test")) with FlatSpec with ShouldMatchers with MockitoSugar with BeforeAndAfterAll with BeforeAndAfterEach{

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



  "ActorProducer" should "pass the message to the consumer, when exchange is synchronous and in-only" in {

    prepareMocks(probe.ref, outCapable = false)

    producer.process(exchange)

    within(1 second){
      probe.expectMsg(message)
    }
  }

  def msg(s: String) = Message(s, Map.empty, camel.context)

  it should "get a response, when exchange is synchronous and out capable" in {
    prepareMocks(echoActor, outCapable = true)

    producer.process(exchange)

    verify(exchange).setResponse(msg("received "+message))
  }

  it should "get a response and async callback as soon as it gets response (but not before), when exchange is non blocking, out capable" in {
    prepareMocks(probe.ref, outCapable = true)
    val doneSync = producer.process(exchange, asyncCallback)

    asyncCallback.expectNoCallWithin(50 millis)
    within(1 second){
      probe.expectMsgType[Message]
      probe.sender ! "some message"
    }
    doneSync should be (false)
    asyncCallback.expectDoneAsyncWithin(1 second)
    verify(exchange).setResponse(msg("some message"))
  }


  it should "get a response and sync callback, when exchange is blocking, out capable" in {
    prepareMocks(echoActor, outCapable = true)

    val producer = new TestableProducer(config(isBlocking=Blocking(1 second)), camel)

    val doneSync = producer.process(exchange, asyncCallback)


    doneSync should be (true)
    //TODO: This is a bit lame test. Happy for any suggestions.
    asyncCallback.expectDoneSyncWithin(0 second)
    verify(exchange).setResponse(msg("received "+message))
  }

  it should "get async callback as soon as it sends a message, when exchange is non blocking, in only and autoAck" in {
    prepareMocks(doNothingActor, outCapable = false)
    val doneSync = producer.process(exchange, asyncCallback)


    doneSync should be (false)
    asyncCallback.expectDoneAsyncWithin(1 second)
    verify(exchange, never()).setResponse(any[Message])
  }

  it should  "timeout when it doesnt get Ack" in {
    prepareMocks(doNothingActor, outCapable = false)
    val producer = new TestableProducer(config(isBlocking = Blocking(10 millis), isAutoAck = false), camel)

    producer.process(exchange, asyncCallback)

    verify(exchange).setFailure(any[Failure])

  }
  
  //TODO: write a test which checks it waits for at least as long as blocking timeout specifies


  def time[A](block : => A) : Duration ={
    val start = System.currentTimeMillis()
    block
    val duration = System.currentTimeMillis() - start
    duration millis
  }

  it should  "timeout when it doesnt get output message, roughly after time specified by blocking timeout parameter" in {
    prepareMocks(doNothingActor, outCapable = true)

    val producer = new TestableProducer(config(isBlocking = Blocking(50 millis), isAutoAck = false), camel)

    val duration = time {
      producer.process(exchange, asyncCallback)
      asyncCallback.awaitCalled()
    }

    duration should be >= (50 millis)
    duration should be < (100 millis)
  }
  
  it should  "timeout when it doesnt get output message" in {
    prepareMocks(doNothingActor, outCapable = true)
    val producer = new TestableProducer(config(isBlocking = Blocking(10 millis), isAutoAck = false), camel)

    producer.process(exchange, asyncCallback)

    verify(exchange).setFailure(any[Failure])
  }

  it should "get async callback as soon as it gets Ack a message, when exchange is non blocking, in only and manualAck" in {

    prepareMocks(probe.ref, outCapable = false)
    val producer = new TestableProducer(config(isAutoAck = false), camel)

    val doneSync = producer.process(exchange, asyncCallback)


    doneSync should be (false)
    within(1 second){
      probe.expectMsgType[Message]
      probe.sender ! Ack
      asyncCallback.expectDoneAsyncWithin(remaining)
    }
    verify(exchange, never()).setResponse(any[Message])
  }

  it should "get sync callback when it gets Ack a message, when exchange is blocking, in only and manualAck" in {

    prepareMocks(probe.ref, outCapable = false)
    val producer = new TestableProducer(config(isBlocking = Blocking(1 second), isAutoAck = false), camel)

    val doneSync = producer.process(exchange, asyncCallback)


    doneSync should be (true)
    within(5 millis){
      probe.expectMsgType[Message]
      probe.sender ! Ack
    }
    asyncCallback.expectDoneSyncWithin(1 second)
    verify(exchange, never()).setResponse(any[Message])
  }

  //TODO: write more tests for process(exchange) method
  
  //TODO: write this test
  it should "fail if expecting Ack or Failure message and some other message is sent as a response, when in-only, manualAck" in  pending
  
  it should "set an exception on exchange when response is Failure, when outCapable" in {
    val failure = Failure(new RuntimeException("some failure"))
    prepareMocks(probe.ref, outCapable = true)
    producer.process(exchange, asyncCallback)

    within(1 second){
      probe.expectMsgType[Message]
      probe.sender ! failure
      asyncCallback.awaitCalled(remaining)
    }

    verify(exchange).setFailure(failure)
  }

  it should "disallow blocking, when in only and autoAck" in {
    prepareMocks(doNothingActor, outCapable = false)
    val producer = new TestableProducer(config(isBlocking=Blocking(1 second)), camel)
    intercept[IllegalStateException]{
      producer.process(exchange, mock[AsyncCallback])
    }
  }


  def doNothingActor = system.actorOf(Props(new Actor {
    protected def receive = { case _ => { /*do nothing*/}}
  }))

  def ackActor = system.actorOf(Props(new Actor {
    protected def receive = { case _ => sender ! Ack}
  }))

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

  def config(actorPath: String = "test-path",  endpointUri: String = "test-uri",  isBlocking: BlockingOrNot = NonBlocking, isAutoAck : Boolean = true) = {
    new ActorEndpointConfig {
      val path = Path(actorPath)
      val getEndpointUri = endpointUri
      blocking = isBlocking
      autoack = isAutoAck
    }
  }

  def prepareMocks(actor: ActorRef, message: Message = message, outCapable: Boolean) {
    when(camel.findActor(any[Path])) thenReturn Option(actor)
    when(exchange.toRequestMessage(any[Map[String, Any]])) thenReturn message
    when(exchange.isOutCapable) thenReturn outCapable
  }

  def echoActor = system.actorOf(Props(new Actor {
    protected def receive = {
      case msg => sender ! "received " + msg
    }
  }))
}