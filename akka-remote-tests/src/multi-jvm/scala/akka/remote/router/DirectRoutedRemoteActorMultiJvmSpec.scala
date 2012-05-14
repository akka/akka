/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.router

import akka.actor.{ Actor, ActorRef, Props }
import akka.remote.AkkaRemoteSpec
import akka.remote.AbstractRemoteActorMultiJvmSpec
import akka.remote.RemoteActorRef
import akka.remote.testconductor.TestConductor
import akka.testkit._
import akka.dispatch.Await
import akka.pattern.ask
import akka.util.Duration

object DirectRoutedRemoteActorMultiJvmSpec extends AbstractRemoteActorMultiJvmSpec {
  override def NrOfNodes = 2

  class SomeActor extends Actor with Serializable {
    def receive = {
      case "identify" ⇒ sender ! self
    }
  }

  import com.typesafe.config.ConfigFactory
  override def commonConfig = ConfigFactory.parseString("""
      akka {
        loglevel = INFO
        actor {
          provider = akka.remote.RemoteActorRefProvider
          deployment {
            /service-hello.remote = %s
          }
          debug {
            receive = on
            fsm = on
          }
        }
        remote {
          transport = akka.remote.testconductor.TestConductorTransport
          log-received-messages = on
          log-sent-messages = on
        }
        testconductor {
          host = localhost
          port = 4712
        }
      }""" format akkaURIs(1))

  def nameConfig(n: Int) = ConfigFactory.parseString("akka.testconductor.name = node" + n).withFallback(nodeConfigs(n))
}

class DirectRoutedRemoteActorMultiJvmNode1 extends AkkaRemoteSpec(DirectRoutedRemoteActorMultiJvmSpec.nameConfig(0)) {
  import DirectRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes
  val tc = TestConductor(system)

  "A new remote actor configured with a Direct router" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" in {
      Await.result(tc.startController(2), Duration.Inf)
      tc.enter("begin", "done")
    }
  }

}

class DirectRoutedRemoteActorMultiJvmNode2 extends AkkaRemoteSpec(DirectRoutedRemoteActorMultiJvmSpec.nameConfig(1))
  with ImplicitSender with DefaultTimeout {

  import DirectRoutedRemoteActorMultiJvmSpec._
  val nodes = NrOfNodes
  val tc = TestConductor(system)

  "A new remote actor configured with a Direct router" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" in {
      Await.result(tc.startClient(4712), Duration.Inf)
      tc.enter("begin")

      val actor = system.actorOf(Props[SomeActor], "service-hello")
      actor.isInstanceOf[RemoteActorRef] must be(true)

      Await.result(actor ? "identify", timeout.duration).asInstanceOf[ActorRef].path.address.hostPort must equal(akkaSpec(0))

      // shut down the actor before we let the other node(s) shut down so we don't try to send
      // "Terminate" to a shut down node
      system.stop(actor)
      tc.enter("done")
    }
  }
}
