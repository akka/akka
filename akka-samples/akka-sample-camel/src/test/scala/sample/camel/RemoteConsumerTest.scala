package sample.camel

import org.scalatest.{ GivenWhenThen, BeforeAndAfterAll, FeatureSpec }

import akka.actor.Actor._
import akka.actor._
import akka.camel._
//import akka.cluster.netty.NettyRemoteSupport
//import akka.cluster.RemoteServerModule

/**
 * @author Martin Krasser
 */
class RemoteConsumerTest /*extends FeatureSpec with BeforeAndAfterAll with GivenWhenThen*/ {
  /* TODO: fix remote test

  import CamelServiceManager._
  import RemoteConsumerTest._

  var server: RemoteServerModule = _

  override protected def beforeAll = {
    registry.shutdownAll

    startCamelService

    remote.shutdown
    remote.asInstanceOf[NettyRemoteSupport].optimizeLocal.set(false)

    server = remote.start(host,port)
  }

  override protected def afterAll = {
    remote.shutdown

    stopCamelService

    registry.shutdownAll
    remote.asInstanceOf[NettyRemoteSupport].optimizeLocal.set(true)
  }

  feature("Publish consumer on remote node") {
    scenario("access published remote consumer") {
      given("a consumer actor")
      val consumer = Actor.actorOf[RemoteConsumer]

      when("registered at the server")
      assert(mandatoryService.awaitEndpointActivation(1) {
        remote.register(consumer)
      })

      then("the published consumer is accessible via its endpoint URI")
      val response = CamelContextManager.mandatoryTemplate.requestBody("direct:remote-consumer", "test")
      assert(response === "remote actor: test")
    }
  }

  feature("Publish typed consumer on remote node") {
    scenario("access published remote consumer method") {
      given("a typed consumer actor")
      when("registered at the server")
      assert(mandatoryService.awaitEndpointActivation(1) {
        remote.registerTypedActor("whatever", TypedActor.newInstance(
          classOf[SampleRemoteTypedConsumer],
          classOf[SampleRemoteTypedConsumerImpl]))
      })
      then("the published method is accessible via its endpoint URI")
      val response = CamelContextManager.mandatoryTemplate.requestBody("direct:remote-typed-consumer", "test")
      assert(response === "remote typed actor: test")
    }
  }

  feature("Publish untyped consumer on remote node") {
    scenario("access published remote untyped consumer") {
      given("an untyped consumer actor")
      val consumer = Actor.actorOf(classOf[SampleRemoteUntypedConsumer])

      when("registered at the server")
      assert(mandatoryService.awaitEndpointActivation(1) {
        remote.register(consumer)
      })
      then("the published untyped consumer is accessible via its endpoint URI")
      val response = CamelContextManager.mandatoryTemplate.requestBodyAndHeader("direct:remote-untyped-consumer", "a", "test", "b")
      assert(response === "a b")
    }
  }*/
}

object RemoteConsumerTest {
  val host = "localhost"
  val port = 7774

  class RemoteConsumer extends Actor with Consumer {
    def endpointUri = "direct:remote-consumer"

    protected def receive = {
      case "init"     ⇒ sender ! "done"
      case m: Message ⇒ sender ! ("remote actor: %s" format m.body)
    }
  }
}
