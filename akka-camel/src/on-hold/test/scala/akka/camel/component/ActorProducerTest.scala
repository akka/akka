package akka.camel.component

import ActorComponentTest._

import java.util.concurrent.{CountDownLatch, TimeoutException, TimeUnit}

import org.apache.camel.{AsyncCallback, ExchangePattern}

import org.junit.{After, Test}
import org.scalatest.junit.JUnitSuite
import org.scalatest.BeforeAndAfterAll

import akka.actor.Actor._
import akka.camel.{Failure, Message}
import akka.camel.CamelTestSupport._

class ActorProducerTest extends JUnitSuite with BeforeAndAfterAll {
  import ActorProducerTest._

  @After def tearDown = registry.shutdownAll

  @Test def shouldSendMessageToActorWithSyncProcessor = {
    val actor = actorOf[Tester1].start
    val latch = (actor !! SetExpectedMessageCount(1)).as[CountDownLatch].get
    val endpoint = actorEndpoint("actor:uuid:%s" format actor.uuid)
    val exchange = endpoint.createExchange(ExchangePattern.InOnly)
    exchange.getIn.setBody("Martin")
    exchange.getIn.setHeader("k1", "v1")
    actorProducer(endpoint).process(exchange)
    assert(latch.await(5000, TimeUnit.MILLISECONDS))
    val reply = (actor !! GetRetainedMessage).get.asInstanceOf[Message]
    assert(reply.body === "Martin")
    assert(reply.headers === Map(Message.MessageExchangeId -> exchange.getExchangeId, "k1" -> "v1"))
  }

  @Test def shouldSendMessageToActorWithAsyncProcessor = {
    val actor = actorOf[Tester1].start
    val latch = (actor !! SetExpectedMessageCount(1)).as[CountDownLatch].get
    val endpoint = actorEndpoint("actor:uuid:%s" format actor.uuid)
    val exchange = endpoint.createExchange(ExchangePattern.InOnly)
    exchange.getIn.setBody("Martin")
    exchange.getIn.setHeader("k1", "v1")
    actorAsyncProducer(endpoint).process(exchange, expectSyncCompletion)
    assert(latch.await(5000, TimeUnit.MILLISECONDS))
    val reply = (actor !! GetRetainedMessage).get.asInstanceOf[Message]
    assert(reply.body === "Martin")
    assert(reply.headers === Map(Message.MessageExchangeId -> exchange.getExchangeId, "k1" -> "v1"))
  }

  @Test def shouldSendMessageToActorAndReceiveResponseWithSyncProcessor = {
    val actor = actorOf(new Tester2 {
      override def response(msg: Message) = Message(super.response(msg), Map("k2" -> "v2"))
    }).start
    val endpoint = actorEndpoint("actor:uuid:%s" format actor.uuid)
    val exchange = endpoint.createExchange(ExchangePattern.InOut)
    exchange.getIn.setBody("Martin")
    exchange.getIn.setHeader("k1", "v1")
    actorProducer(endpoint).process(exchange)
    assert(exchange.getOut.getBody === "Hello Martin")
    assert(exchange.getOut.getHeader("k2") === "v2")
  }

  @Test def shouldSendMessageToActorAndReceiveResponseWithAsyncProcessor = {
    val actor = actorOf(new Tester2 {
      override def response(msg: Message) = Message(super.response(msg), Map("k2" -> "v2"))
    }).start
    val completion = expectAsyncCompletion
    val endpoint = actorEndpoint("actor:uuid:%s" format actor.uuid)
    val exchange = endpoint.createExchange(ExchangePattern.InOut)
    exchange.getIn.setBody("Martin")
    exchange.getIn.setHeader("k1", "v1")
    actorAsyncProducer(endpoint).process(exchange, completion)
    assert(completion.latch.await(5000, TimeUnit.MILLISECONDS))
    assert(exchange.getOut.getBody === "Hello Martin")
    assert(exchange.getOut.getHeader("k2") === "v2")
  }

  @Test def shouldSendMessageToActorAndReceiveFailureWithAsyncProcessor = {
    val actor = actorOf(new Tester2 {
      override def response(msg: Message) = Failure(new Exception("testmsg"), Map("k3" -> "v3"))
    }).start
    val completion = expectAsyncCompletion
    val endpoint = actorEndpoint("actor:uuid:%s" format actor.uuid)
    val exchange = endpoint.createExchange(ExchangePattern.InOut)
    exchange.getIn.setBody("Martin")
    exchange.getIn.setHeader("k1", "v1")
    actorAsyncProducer(endpoint).process(exchange, completion)
    assert(completion.latch.await(5000, TimeUnit.MILLISECONDS))
    assert(exchange.getException.getMessage === "testmsg")
    assert(exchange.getOut.getBody === null)
    assert(exchange.getOut.getHeader("k3") === null) // headers from failure message are currently ignored
  }

  @Test def shouldSendMessageToActorAndReceiveAckWithAsyncProcessor = {
    val actor = actorOf(new Tester2 {
      override def response(msg: Message) = akka.camel.Ack
    }).start
    val completion = expectAsyncCompletion
    val endpoint = actorEndpoint("actor:uuid:%s?autoack=false" format actor.uuid)
    val exchange = endpoint.createExchange(ExchangePattern.InOnly)
    exchange.getIn.setBody("Martin")
    actorAsyncProducer(endpoint).process(exchange, completion)
    assert(completion.latch.await(5000, TimeUnit.MILLISECONDS))
    assert(exchange.getIn.getBody === "Martin")
    assert(exchange.getOut.getBody === null)
  }

  @Test def shouldDynamicallyRouteMessageToActorWithDefaultId = {
    val actor1 = actorOf[Tester1]
    val actor2 = actorOf[Tester1]
    actor1.id = "x"
    actor2.id = "y"
    actor1.start
    actor2.start
    val latch1 = (actor1 !! SetExpectedMessageCount(1)).as[CountDownLatch].get
    val latch2 = (actor2 !! SetExpectedMessageCount(1)).as[CountDownLatch].get
    val endpoint = actorEndpoint("actor:id:%s" format actor1.id)
    val exchange1 = endpoint.createExchange(ExchangePattern.InOnly)
    val exchange2 = endpoint.createExchange(ExchangePattern.InOnly)
    exchange1.getIn.setBody("Test1")
    exchange2.getIn.setBody("Test2")
    exchange2.getIn.setHeader(ActorComponent.ActorIdentifier, actor2.id)
    actorProducer(endpoint).process(exchange1)
    actorProducer(endpoint).process(exchange2)
    assert(latch1.await(5, TimeUnit.SECONDS))
    assert(latch2.await(5, TimeUnit.SECONDS))
    val reply1 = (actor1 !! GetRetainedMessage).get.asInstanceOf[Message]
    val reply2 = (actor2 !! GetRetainedMessage).get.asInstanceOf[Message]
    assert(reply1.body === "Test1")
    assert(reply2.body === "Test2")
  }

  @Test def shouldDynamicallyRouteMessageToActorWithoutDefaultId = {
    val actor1 = actorOf[Tester1]
    val actor2 = actorOf[Tester1]
    actor1.id = "x"
    actor2.id = "y"
    actor1.start
    actor2.start
    val latch1 = (actor1 !! SetExpectedMessageCount(1)).as[CountDownLatch].get
    val latch2 = (actor2 !! SetExpectedMessageCount(1)).as[CountDownLatch].get
    val endpoint = actorEndpoint("actor:id:")
    val exchange1 = endpoint.createExchange(ExchangePattern.InOnly)
    val exchange2 = endpoint.createExchange(ExchangePattern.InOnly)
    exchange1.getIn.setBody("Test1")
    exchange2.getIn.setBody("Test2")
    exchange1.getIn.setHeader(ActorComponent.ActorIdentifier, actor1.id)
    exchange2.getIn.setHeader(ActorComponent.ActorIdentifier, actor2.id)
    actorProducer(endpoint).process(exchange1)
    actorProducer(endpoint).process(exchange2)
    assert(latch1.await(5, TimeUnit.SECONDS))
    assert(latch2.await(5, TimeUnit.SECONDS))
    val reply1 = (actor1 !! GetRetainedMessage).get.asInstanceOf[Message]
    val reply2 = (actor2 !! GetRetainedMessage).get.asInstanceOf[Message]
    assert(reply1.body === "Test1")
    assert(reply2.body === "Test2")
  }

  @Test def shouldDynamicallyRouteMessageToActorWithDefaultUuid = {
    val actor1 = actorOf[Tester1].start
    val actor2 = actorOf[Tester1].start
    val latch1 = (actor1 !! SetExpectedMessageCount(1)).as[CountDownLatch].get
    val latch2 = (actor2 !! SetExpectedMessageCount(1)).as[CountDownLatch].get
    val endpoint = actorEndpoint("actor:uuid:%s" format actor1.uuid)
    val exchange1 = endpoint.createExchange(ExchangePattern.InOnly)
    val exchange2 = endpoint.createExchange(ExchangePattern.InOnly)
    exchange1.getIn.setBody("Test1")
    exchange2.getIn.setBody("Test2")
    exchange2.getIn.setHeader(ActorComponent.ActorIdentifier, actor2.uuid.toString)
    actorProducer(endpoint).process(exchange1)
    actorProducer(endpoint).process(exchange2)
    assert(latch1.await(5, TimeUnit.SECONDS))
    assert(latch2.await(5, TimeUnit.SECONDS))
    val reply1 = (actor1 !! GetRetainedMessage).get.asInstanceOf[Message]
    val reply2 = (actor2 !! GetRetainedMessage).get.asInstanceOf[Message]
    assert(reply1.body === "Test1")
    assert(reply2.body === "Test2")
  }

  @Test def shouldDynamicallyRouteMessageToActorWithoutDefaultUuid = {
    val actor1 = actorOf[Tester1].start
    val actor2 = actorOf[Tester1].start
    val latch1 = (actor1 !! SetExpectedMessageCount(1)).as[CountDownLatch].get
    val latch2 = (actor2 !! SetExpectedMessageCount(1)).as[CountDownLatch].get
    val endpoint = actorEndpoint("actor:uuid:")
    val exchange1 = endpoint.createExchange(ExchangePattern.InOnly)
    val exchange2 = endpoint.createExchange(ExchangePattern.InOnly)
    exchange1.getIn.setBody("Test1")
    exchange2.getIn.setBody("Test2")
    exchange1.getIn.setHeader(ActorComponent.ActorIdentifier, actor1.uuid)
    exchange2.getIn.setHeader(ActorComponent.ActorIdentifier, actor2.uuid.toString)
    actorProducer(endpoint).process(exchange1)
    actorProducer(endpoint).process(exchange2)
    assert(latch1.await(5, TimeUnit.SECONDS))
    assert(latch2.await(5, TimeUnit.SECONDS))
    val reply1 = (actor1 !! GetRetainedMessage).get.asInstanceOf[Message]
    val reply2 = (actor2 !! GetRetainedMessage).get.asInstanceOf[Message]
    assert(reply1.body === "Test1")
    assert(reply2.body === "Test2")
  }

  @Test def shouldThrowExceptionWhenIdNotSet: Unit = {
    val actor = actorOf[Tester1].start
    val latch = (actor !! SetExpectedMessageCount(1)).as[CountDownLatch].get
    val endpoint = actorEndpoint("actor:id:")
    intercept[ActorIdentifierNotSetException] {
      actorProducer(endpoint).process(endpoint.createExchange(ExchangePattern.InOnly))
    }
  }

  @Test def shouldThrowExceptionWhenUuidNotSet: Unit = {
    val actor = actorOf[Tester1].start
    val latch = (actor !! SetExpectedMessageCount(1)).as[CountDownLatch].get
    val endpoint = actorEndpoint("actor:uuid:")
    intercept[ActorIdentifierNotSetException] {
      actorProducer(endpoint).process(endpoint.createExchange(ExchangePattern.InOnly))
    }
  }

  @Test def shouldSendMessageToActorAndTimeout(): Unit = {
    val actor = actorOf[Tester3].start
    val endpoint = actorEndpoint("actor:uuid:%s" format actor.uuid)
    val exchange = endpoint.createExchange(ExchangePattern.InOut)
    exchange.getIn.setBody("Martin")
    intercept[TimeoutException] {
      endpoint.createProducer.process(exchange)
    }
  }
}

object ActorProducerTest {
  def expectSyncCompletion = new AsyncCallback {
    def done(doneSync: Boolean) = assert(doneSync)
  }

  def expectAsyncCompletion = new AsyncCallback {
    val latch = new CountDownLatch(1);
    def done(doneSync: Boolean) = {
      assert(!doneSync)
      latch.countDown
    }
  }
}
