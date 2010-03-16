package se.scalablesolutions.akka.camel

import org.apache.camel.{Exchange, Processor}
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.component.mock.MockEndpoint
import org.junit.Assert._
import org.junit.{Test, After, Before}
import org.scalatest.junit.JUnitSuite

import se.scalablesolutions.akka.actor.Actor

class ProducerTest extends JUnitSuite {

  //
  // TODO: extend/rewrite unit tests
  // These tests currently only ensure proper functioning of basic features.
  //

  import CamelContextManager._

  var mock: MockEndpoint = _

  @Before def setUp = {
    init
    context.addRoutes(new TestRouteBuilder)
    start
    mock = context.getEndpoint("mock:mock", classOf[MockEndpoint])
  }

  @After def tearDown = {
    stop
  }

  //
  // TODO: test replies to messages sent with ! (bang)
  // TODO: test copying of custom message headers 
  //

  @Test def shouldProduceMessageSyncAndReceiveResponse = {
    val producer = new TestProducer("direct:input2", false, false).start
    val message = Message("test1", Map(Message.MessageExchangeId -> "123"))
    val expected = Message("Hello test1", Map(Message.MessageExchangeId -> "123"))
    assertEquals(expected, producer !! message get)
    producer.stop
  }

  @Test def shouldProduceMessageSyncAndReceiveFailure = {
    val producer = new TestProducer("direct:input2", false, false).start
    val message = Message("fail", Map(Message.MessageExchangeId -> "123"))
    val result = producer.!![Failure](message).get
    assertEquals("failure", result.cause.getMessage)
    assertEquals(Map(Message.MessageExchangeId -> "123"), result.headers)
    producer.stop
  }

  @Test def shouldProduceMessageAsyncAndReceiveResponse = {
    val producer = new TestProducer("direct:input2", true, false).start
    val message = Message("test2", Map(Message.MessageExchangeId -> "124"))
    val expected = Message("Hello test2", Map(Message.MessageExchangeId -> "124"))
    assertEquals(expected, producer !! message get)
    producer.stop
  }

  @Test def shouldProduceMessageAsyncAndReceiveFailure = {
    val producer = new TestProducer("direct:input2", true, false).start
    val message = Message("fail", Map(Message.MessageExchangeId -> "124"))
    val result = producer.!![Failure](message).get
    assertEquals("failure", result.cause.getMessage)
    assertEquals(Map(Message.MessageExchangeId -> "124"), result.headers)
    producer.stop
  }

  @Test def shouldProduceMessageSyncWithoutReceivingResponse = {
    val producer = new TestProducer("direct:input1", false, true).start
    mock.expectedBodiesReceived("test3")
    producer.!("test3")(None)
    producer.stop
  }

  @Test def shouldProduceMessageAsyncAndReceiveResponseSync = {
    val producer = new TestProducer("direct:input1", true, true).start
    mock.expectedBodiesReceived("test4")
    producer.!("test4")(None)
    producer.stop
  }

  class TestProducer(uri:String, prodAsync: Boolean, prodOneway: Boolean) extends Actor with Producer {
    override def async = prodAsync
    override def oneway = prodOneway
    def endpointUri = uri
    def receive = produce
  }

  class TestRouteBuilder extends RouteBuilder {
    def configure {
      from("direct:input1").to("mock:mock")
      from("direct:input2").process(new Processor() {
        def process(exchange: Exchange) = {
          val body = exchange.getIn.getBody
          body match {
            case "fail" => throw new Exception("failure")
            case body   => exchange.getOut.setBody("Hello %s" format body)
          }
        }
      })
    }
  }

}