package akka.camel

import component.{Path, ActorEndpointConfig, TestableProducer}
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.mock.MockitoSugar
import org.mockito.Matchers.{eq => the, any}
import org.mockito.Mockito._
import org.apache.camel.AsyncCallback
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import akka.actor.{ActorRef, Props, ActorSystem, Actor}
import akka.util.duration._
import org.scalatest.{BeforeAndAfterEach, FlatSpec}
import akka.util.Duration
//TODO: this whole test doesn't seem right with FlatSpec, investigate other options, maybe given-when-then style
class ActorProducerTest extends FlatSpec with ShouldMatchers with MockitoSugar with BeforeAndAfterEach{

  var registry :ConsumerRegistry = _
  var exchange : CamelExchangeAdapter = _
  var callback : AsyncCallback = _

  var producer : TestableProducer = _
  var message : Message = _
  var system : ActorSystem = _


  override protected def beforeEach() {
    registry = mock[ConsumerRegistry]
    exchange = mock[CamelExchangeAdapter]
    callback = mock[AsyncCallback]

    producer = new TestableProducer(config(), registry)
    message = new Message()
    system = ActorSystem("test")
  }

  override protected def afterEach() {
    system.shutdown()
  }

  "ActorProducer" should "pass the message to the consumer, when exchange is synchronous and in-only" in {
    val (receivedLatch, receivedMessage,  actor) = latchActor

    prepareMocks(actor, message, outCapable = false)

    producer.process(exchange)

    if (!receivedLatch.await(1, TimeUnit.SECONDS)) fail("Expected to get a message but got non within the timeout");
    receivedMessage.get() should be(message)
  }

  it should "get a response, when exchange is synchronous and out capable" in {
    prepareMocks(echoActor, message, outCapable = true)

    producer.process(exchange)

    verify(exchange).fromResponseMessage(the(new Message("received "+message)))
  }

  it should "get a response and async callback as soon as it gets response, when exchange is non blocking, out capable" in {
    prepareMocks(echoActor, message, outCapable = true)
    val (getCallbackValueWithin, asyncCallback) = createAsyncCallback
    val doneSync = producer.process(exchange, asyncCallback)

    //TODO: we should test it doesn't act before it gets response
    doneSync should be (false)
    getCallbackValueWithin(1 second) should be (false)
    verify(exchange).fromResponseMessage(new Message("received "+message))
  }


  it should "get a response and sync callback, when exchange is blocking, out capable" in {
    prepareMocks(echoActor, message, outCapable = true)

    val producer = new TestableProducer(config(isBlocking=Blocking(1 second)), registry)

    val (getCallbackValueWithin, asyncCallback) = createAsyncCallback
    val doneSync = producer.process(exchange, asyncCallback)


    doneSync should be (true)
    //TODO: This is a bit lame test. Happy for any suggestions.
    getCallbackValueWithin(1 second) should be (true)
    verify(exchange).fromResponseMessage(new Message("received "+message))
  }

  it should "get async callback as soon as it sends a message, when exchange is non blocking, in only and autoAck" in {
    prepareMocks(doNothingActor, message, outCapable = false)
    val (getCallbackValueWithin, asyncCallback) = createAsyncCallback
    val doneSync = producer.process(exchange, asyncCallback)


    doneSync should be (false)
    getCallbackValueWithin(1 second) should be (false)
    verify(exchange, never()).fromResponseMessage(any[Message])
  }

  it should  "timeout when it doesnt get Ack" in pending
  it should  "timeout when it doesnt get output message" in pending

  it should "get async callback as soon as it gets Ack a message, when exchange is non blocking, in only and manualAck" in {
    
    prepareMocks(ackActor, message, outCapable = false)
    val producer = new TestableProducer(config(isAutoAck = false), registry)
    
    val (getCallbackValueWithin, asyncCallback) = createAsyncCallback
    val doneSync = producer.process(exchange, asyncCallback)


    doneSync should be (false)
    getCallbackValueWithin(1 second) should be (false)
    verify(exchange, never()).fromResponseMessage(any[Message])
  }

  it should "get sync callback when it gets Ack a message, when exchange is blocking, in only and manualAck" in {

    prepareMocks(ackActor, message, outCapable = false)
    val producer = new TestableProducer(config(isBlocking = Blocking(1 second), isAutoAck = false), registry)

    val (getCallbackValueWithin, asyncCallback) = createAsyncCallback
    val doneSync = producer.process(exchange, asyncCallback)


    doneSync should be (true)
    getCallbackValueWithin(0 second) should be (true)
    verify(exchange, never()).fromResponseMessage(any[Message])
  }

  it should "disallow blocking, in only and autoAck" in {
    prepareMocks(doNothingActor, message, outCapable = false)
    val producer = new TestableProducer(config(isBlocking=Blocking(1 second)), registry)
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

  def createAsyncCallback = {
    val callbackReceived = new CountDownLatch(1)
    val callbackValue = new AtomicBoolean()
    val callback = new AsyncCallback {
      def done(doneSync: Boolean) {
        callbackValue set doneSync
        callbackReceived.countDown()
      }
    }
    def getValueWithin(timeout:Duration) ={
      if (! callbackReceived.await(timeout.toNanos, TimeUnit.NANOSECONDS)) fail("Callback not received!")
      callbackValue.get
    }
    (getValueWithin _, callback)
  }

  def config(actorPath: String = "test-path",  endpointUri: String = "test-uri",  isBlocking: BlockingOrNot = NonBlocking, isAutoAck : Boolean = true) = {
    new ActorEndpointConfig {
      val path = Path(actorPath)
      val getEndpointUri = endpointUri
      blocking = isBlocking
      autoack = isAutoAck
    }
  }

  def prepareMocks(actor: ActorRef, message: Message, outCapable: Boolean) {
    when(registry.findConsumer(any[Path])) thenReturn Option(actor)
    when(exchange.toRequestMessage(any[Map[String, Any]])) thenReturn message
    when(exchange.isOutCapable) thenReturn outCapable
  }

  def latchActor = {
    val receivedLatch = new CountDownLatch(1)
    val message = new AtomicReference[Any]()
    val actor = system.actorOf(Props(new Actor {
      protected def receive = {
        case m => receivedLatch.countDown(); message.set(m)
      }
    }))
    (receivedLatch, message,  actor)
  }

  def echoActor = system.actorOf(Props(new Actor {
    protected def receive = {
      case msg => sender ! "received " + msg
    }
  }))
}