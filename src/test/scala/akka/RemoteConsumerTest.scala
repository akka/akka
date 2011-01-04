package akka.camel

import java.util.concurrent.{CountDownLatch, TimeUnit}

import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, FeatureSpec}

import akka.actor._
import akka.actor.Actor._

/**
 * @author Martin Krasser
 */
class RemoteConsumerTest extends FeatureSpec with BeforeAndAfterAll with GivenWhenThen {
  import CamelServiceManager._
  import RemoteConsumerTest._

  override protected def beforeAll = {
    registry.shutdownAll

    startCamelService

    remote.shutdown
    remote.start(host,port)

    Thread.sleep(1000)
  }

  override protected def afterAll = {
    remote.shutdown

    stopCamelService

    registry.shutdownAll

    Thread.sleep(1000)
  }

  feature("Publish consumer on remote node") {
    scenario("access published remote consumer") {
      given("a client-initiated remote consumer")
      val consumer = remote.actorOf[RemoteConsumer](host, port).start

      when("remote consumer publication is triggered")
      assert(mandatoryService.awaitEndpointActivation(1) {
        consumer !! "init"
      })

      then("the published consumer is accessible via its endpoint URI")
      val response = CamelContextManager.mandatoryTemplate.requestBody("direct:remote-consumer", "test")
      assert(response === "remote actor: test")
    }
  }

  feature("Publish typed consumer on remote node") {
    scenario("access published remote consumer method") {
      given("a client-initiated remote typed consumer")
      val consumer = TypedActor.newRemoteInstance(classOf[SampleRemoteTypedConsumer], classOf[SampleRemoteTypedConsumerImpl], host, port)

      when("remote typed consumer publication is triggered")
      assert(mandatoryService.awaitEndpointActivation(1) {
        consumer.foo("init")
      })
      then("the published method is accessible via its endpoint URI")
      val response = CamelContextManager.mandatoryTemplate.requestBody("direct:remote-typed-consumer", "test")
      assert(response === "remote typed actor: test")
    }
  }

  feature("Publish untyped consumer on remote node") {
    scenario("access published remote untyped consumer") {
      given("a client-initiated remote untyped consumer")
      val consumer = remote.actorOf(classOf[SampleRemoteUntypedConsumer], host, port).start

      when("remote untyped consumer publication is triggered")
      assert(mandatoryService.awaitEndpointActivation(1) {
        consumer.sendRequestReply(Message("init", Map("test" -> "init")))
      })
      then("the published untyped consumer is accessible via its endpoint URI")
      val response = CamelContextManager.mandatoryTemplate.requestBodyAndHeader("direct:remote-untyped-consumer", "a", "test", "b")
      assert(response === "a b")
    }
  }
}

object RemoteConsumerTest {
  val host = "localhost"
  val port = 7774

  class RemoteConsumer extends Actor with Consumer {
    def endpointUri = "direct:remote-consumer"

    protected def receive = {
      case "init"     => self.reply("done")
      case m: Message => self.reply("remote actor: %s" format m.body)
    }
  }
}
