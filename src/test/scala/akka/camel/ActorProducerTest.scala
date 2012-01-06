package akka.camel

import component.{Path, ActorEndpointConfig, TestableProducer}
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.mock.MockitoSugar
import org.mockito.Matchers.{eq => the, any}
import org.mockito.Mockito._
import org.apache.camel.AsyncCallback
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import akka.actor.{ActorRef, Props, ActorSystem, Actor}
import akka.util.duration._
import akka.util.Duration
import akka.testkit.{TestKit, TestProbe}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpec}
import java.lang.String
import org.mockito.stubbing.Answer
import org.mockito.invocation.InvocationOnMock

//TODO: this whole test doesn't seem right with FlatSpec, investigate other options, maybe given-when-then style
class ActorProducerTest extends TestKit(ActorSystem("test")) with FlatSpec with ShouldMatchers with MockitoSugar with BeforeAndAfterAll with BeforeAndAfterEach{

  var camel : Camel  = _
  var exchange : CamelExchangeAdapter = _
  var callback : AsyncCallback = _

  var producer : TestableProducer = _
  var message : Message = _


  override protected def beforeEach() {
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

    val actor = TestProbe()
    prepareMocks(actor.ref, message, outCapable = false)

    producer.process(exchange)

    within(1 second){
      actor.expectMsg(message)
    }
  }

  def newMessage(s: String) = Message(s, Map.empty, camel)

  it should "get a response, when exchange is synchronous and out capable" in {
    prepareMocks(echoActor, message, outCapable = true)

    producer.process(exchange)

    verify(exchange).fromResponseMessage(newMessage("received "+message))
  }

  it should "get a response and async callback as soon as it gets response, when exchange is non blocking, out capable" in {
    prepareMocks(echoActor, message, outCapable = true)
    val asyncCallback = createAsyncCallback
    val doneSync = producer.process(exchange, asyncCallback)

    //TODO: we should test it doesn't act before it gets response
    doneSync should be (false)
    asyncCallback.valueWithin(1 second) should be (false)
    verify(exchange).fromResponseMessage(newMessage("received "+message))
  }


  it should "get a response and sync callback, when exchange is blocking, out capable" in {
    prepareMocks(echoActor, message, outCapable = true)

    val producer = new TestableProducer(config(isBlocking=Blocking(1 second)), camel)

    val asyncCallback = createAsyncCallback
    val doneSync = producer.process(exchange, asyncCallback)


    doneSync should be (true)
    //TODO: This is a bit lame test. Happy for any suggestions.
    asyncCallback.valueWithin(0 second) should be (true)
    verify(exchange).fromResponseMessage(newMessage("received "+message))
  }

  it should "get async callback as soon as it sends a message, when exchange is non blocking, in only and autoAck" in {
    prepareMocks(doNothingActor, message, outCapable = false)
    val asyncCallback = createAsyncCallback
    val doneSync = producer.process(exchange, asyncCallback)


    doneSync should be (false)
    asyncCallback.valueWithin(1 second) should be (false)
    verify(exchange, never()).fromResponseMessage(any[Message])
  }

  it should  "timeout when it doesnt get Ack" in {
    prepareMocks(doNothingActor, message, outCapable = false)
    val producer = new TestableProducer(config(isBlocking = Blocking(10 millis), isAutoAck = false), camel)

    val asyncCallback = createAsyncCallback
    producer.process(exchange, asyncCallback)

    verify(exchange).fromFailureMessage(any[Failure])

  }

  it should  "timeout when it doesnt get output message" in {
    prepareMocks(doNothingActor, message, outCapable = true)
    val producer = new TestableProducer(config(isBlocking = Blocking(10 millis), isAutoAck = false), camel)

    val asyncCallback = createAsyncCallback
    producer.process(exchange, asyncCallback)

    verify(exchange).fromFailureMessage(any[Failure])
  }

  it should "get async callback as soon as it gets Ack a message, when exchange is non blocking, in only and manualAck" in {

    val actor = TestProbe()
    prepareMocks(actor.ref, message, outCapable = false)
    val producer = new TestableProducer(config(isAutoAck = false), camel)

    val asyncCallback = createAsyncCallback
    val doneSync = producer.process(exchange, asyncCallback)


    doneSync should be (false)
    within(1 second){
      actor.expectMsgType[Message]
      actor.sender ! Ack
      asyncCallback.valueWithin(remaining) should be (false)
    }
    verify(exchange, never()).fromResponseMessage(any[Message])
  }

  it should "get sync callback when it gets Ack a message, when exchange is blocking, in only and manualAck" in {

    val actor = TestProbe()
    prepareMocks(actor.ref, message, outCapable = false)
    val producer = new TestableProducer(config(isBlocking = Blocking(1 second), isAutoAck = false), camel)

    val asyncCallback = createAsyncCallback
    val doneSync = producer.process(exchange, asyncCallback)


    doneSync should be (true)
    within(5 millis){
      actor.expectMsgType[Message]
      actor.sender ! Ack
    }
    asyncCallback.valueWithin(1 second) should be (true)
    verify(exchange, never()).fromResponseMessage(any[Message])
  }

  it should "disallow blocking, when in only and autoAck" in {
    prepareMocks(doNothingActor, message, outCapable = false)
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

  def createAsyncCallback =  new AsyncCallback {
    val callbackReceived = new CountDownLatch(1)
    val callbackValue = new AtomicBoolean()

    def done(doneSync: Boolean) {
      callbackValue set doneSync
      callbackReceived.countDown()
    }

    def valueWithin(implicit timeout:Duration) ={
      if (! callbackReceived.await(timeout.toNanos, TimeUnit.NANOSECONDS)) fail("Callback not received!")
      callbackValue.get
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

  def prepareMocks(actor: ActorRef, message: Message, outCapable: Boolean) {
    when(camel.message(any[Any])).thenAnswer(new Answer[Message]{
      def answer(invocation: InvocationOnMock) = Message(invocation.getArguments()(0), Map.empty, camel)
    })
    when(camel.findConsumer(any[Path])) thenReturn Option(actor)
    when(exchange.toRequestMessage(any[Map[String, Any]])) thenReturn message
    when(exchange.isOutCapable) thenReturn outCapable
  }

  def echoActor = system.actorOf(Props(new Actor {
    protected def receive = {
      case msg => sender ! "received " + msg
    }
  }))
}