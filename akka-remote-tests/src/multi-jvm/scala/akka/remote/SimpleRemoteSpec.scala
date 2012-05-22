/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.dispatch.Await
import akka.pattern.ask
import akka.remote.testconductor.TestConductor
import akka.testkit.DefaultTimeout
import akka.testkit.ImplicitSender
import akka.util.Duration
import com.typesafe.config.ConfigFactory

object SimpleRemoteMultiJvmSpec extends AbstractRemoteActorMultiJvmSpec {
  override def NrOfNodes = 2

  class SomeActor extends Actor with Serializable {
    def receive = {
      case "identify" ⇒ sender ! self
    }
  }

  override def commonConfig = ConfigFactory.parseString("""
      akka {
        loglevel = INFO
        actor {
          provider = akka.remote.RemoteActorRefProvider
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
      }""")

  def nameConfig(n: Int) = ConfigFactory.parseString("akka.testconductor.name = node" + n).withFallback(nodeConfigs(n))
}

class SimpleRemoteMultiJvmNode1 extends AkkaRemoteSpec(SimpleRemoteMultiJvmSpec.nameConfig(0)) {
  import SimpleRemoteMultiJvmSpec._
  val nodes = NrOfNodes
  val tc = TestConductor(system)

  "lookup remote actor" in {
    Await.result(tc.startController(2), Duration.Inf)
    system.actorOf(Props[SomeActor], "service-hello")
    tc.enter("begin", "done")
  }

}

class SimpleRemoteMultiJvmNode2 extends AkkaRemoteSpec(SimpleRemoteMultiJvmSpec.nameConfig(1))
  with ImplicitSender with DefaultTimeout {

  import SimpleRemoteMultiJvmSpec._
  val nodes = NrOfNodes
  val tc = TestConductor(system)

  "lookup remote actor" in {
    Await.result(tc.startClient(4712), Duration.Inf)
    tc.enter("begin")
    log.info("### begin ok")
    val actor = system.actorFor("akka://" + akkaSpec(0) + "/user/service-hello")
    log.info("### actor lookup " + akkaSpec(0) + "/service-hello")
    actor.isInstanceOf[RemoteActorRef] must be(true)
    Await.result(actor ? "identify", timeout.duration).asInstanceOf[ActorRef].path.address.hostPort must equal(akkaSpec(0))
    log.info("### actor ok")
    tc.enter("done")
  }

}
