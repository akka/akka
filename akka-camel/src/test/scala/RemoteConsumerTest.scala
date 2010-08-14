package se.scalablesolutions.akka.camel

import java.util.concurrent.{CountDownLatch, TimeUnit}

import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, FeatureSpec}

import se.scalablesolutions.akka.actor._
import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.remote.{RemoteClient, RemoteServer}

/**
 * @author Martin Krasser
 */
class RemoteConsumerTest extends FeatureSpec with BeforeAndAfterAll with GivenWhenThen {
  import RemoteConsumerTest._

  var service: CamelService = _
  var server: RemoteServer = _

  override protected def beforeAll = {
    ActorRegistry.shutdownAll

    service = CamelServiceFactory.createCamelService
    service.load

    server = new RemoteServer()
    server.start(host, port)

    Thread.sleep(1000)
  }

  override protected def afterAll = {
    server.shutdown
    service.unload

    RemoteClient.shutdownAll
    ActorRegistry.shutdownAll

    Thread.sleep(1000)
  }

  feature("Publish consumer on remote node") {
    scenario("access published remote consumer") {
      given("a client-initiated remote consumer")
      val consumer = actorOf[RemoteConsumer].start

      when("remote consumer publication is triggered")
      var latch = service.expectEndpointActivationCount(1)
      consumer !! "init"
      assert(latch.await(5000, TimeUnit.MILLISECONDS))

      then("the published consumer is accessible via its endpoint URI")
      val response = CamelContextManager.template.requestBody("direct:remote-consumer", "test")
      assert(response === "remote actor: test")
    }
  }

  feature("Publish typed consumer on remote node") {
    scenario("access published remote consumer method") {
      given("a client-initiated remote typed consumer")
      val consumer = TypedActor.newRemoteInstance(classOf[SampleRemoteTypedConsumer], classOf[SampleRemoteTypedConsumerImpl], host, port)

      when("remote typed consumer publication is triggered")
      var latch = service.expectEndpointActivationCount(1)
      consumer.foo("init")
      assert(latch.await(5000, TimeUnit.MILLISECONDS))

      then("the published method is accessible via its endpoint URI")
      val response = CamelContextManager.template.requestBody("direct:remote-typed-consumer", "test")
      assert(response === "remote typed actor: test")
    }
  }

  feature("Publish untyped consumer on remote node") {
    scenario("access published remote untyped consumer") {
      given("a client-initiated remote untyped consumer")
      val consumer = UntypedActor.actorOf(classOf[SampleRemoteUntypedConsumer]).start

      when("remote untyped consumer publication is triggered")
      var latch = service.expectEndpointActivationCount(1)
      consumer.sendRequestReply(Message("init", Map("test" -> "init")))
      assert(latch.await(5000, TimeUnit.MILLISECONDS))

      then("the published untyped consumer is accessible via its endpoint URI")
      val response = CamelContextManager.template.requestBodyAndHeader("direct:remote-untyped-consumer", "a", "test", "b")
      assert(response === "a b")
    }
  }
}

object RemoteConsumerTest {
  val host = "localhost"
  val port = 7774

  class RemoteConsumer extends RemoteActor(host, port) with Consumer {
    def endpointUri = "direct:remote-consumer"

    protected def receive = {
      case "init"     => self.reply("done")
      case m: Message => self.reply("remote actor: %s" format m.body)
    }
  }
}
