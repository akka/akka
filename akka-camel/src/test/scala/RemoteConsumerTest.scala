package se.scalablesolutions.akka.camel

import java.util.concurrent.{CountDownLatch, TimeUnit}

import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, FeatureSpec}

import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.actor.{ActiveObject, ActorRegistry, RemoteActor}
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

    service = CamelService.newInstance
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

  feature("Client-initiated remote consumer actor") {
    scenario("access published remote consumer actor") {
      given("a client-initiated remote consumer actor")
      val consumer = actorOf[RemoteConsumer].start

      when("remote consumer publication is triggered")
      val latch = (service.consumerPublisher !! SetExpectedMessageCount(1)).as[CountDownLatch].get
      consumer !! "init"
      assert(latch.await(5000, TimeUnit.MILLISECONDS))

      then("the published actor is accessible via its endpoint URI")
      val response = CamelContextManager.template.requestBody("direct:remote-actor", "test")
      assert(response === "remote actor: test")
    }
  }

  /* TODO: enable once issues with remote active objects are resolved
  feature("Client-initiated remote consumer active object") {
    scenario("access published remote consumer method") {
      given("a client-initiated remote consumer active object")
      val consumer = ActiveObject.newRemoteInstance(classOf[PojoRemote], host, port)

      when("remote consumer publication is triggered")
      val latch = service.consumerPublisher.!![CountDownLatch](SetExpectedMessageCount(1)).get
      consumer.foo("init")
      assert(latch.await(5000, TimeUnit.MILLISECONDS))

      then("the published method is accessible via its endpoint URI")
      val response = CamelContextManager.template.requestBody("direct:remote-active-object", "test")
      assert(response === "remote active object: test")
    }
  }
  */
}

object RemoteConsumerTest {
  val host = "localhost"
  val port = 7774

  class RemoteConsumer extends RemoteActor(host, port) with Consumer {
    def endpointUri = "direct:remote-actor"

    protected def receive = {
      case "init"     => self.reply("done")
      case m: Message => self.reply("remote actor: %s" format m.body)
    }
  }
}
