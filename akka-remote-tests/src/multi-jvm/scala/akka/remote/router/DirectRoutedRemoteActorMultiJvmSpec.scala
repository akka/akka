/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.router

import com.typesafe.config.ConfigFactory

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.pattern.ask
import akka.remote.RemoteActorRef
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._

object DirectRoutedRemoteActorMultiJvmSpec extends MultiNodeConfig {

  class SomeActor extends Actor with Serializable {
    def receive = {
      case "identify" ⇒ sender ! self
    }
  }

  commonConfig(debugConfig(on = false))

  val master = role("master")
  val slave = role("slave")

  deployOn(master, """/service-hello.remote = "@slave@" """)
  
  deployOnAll("""/service-hello2.remote = "@slave@" """)
}

class DirectRoutedRemoteActorMultiJvmNode1 extends DirectRoutedRemoteActorSpec
class DirectRoutedRemoteActorMultiJvmNode2 extends DirectRoutedRemoteActorSpec

class DirectRoutedRemoteActorSpec extends MultiNodeSpec(DirectRoutedRemoteActorMultiJvmSpec)
  with ImplicitSender with DefaultTimeout {
  import DirectRoutedRemoteActorMultiJvmSpec._

  def initialParticipants = 2

  "A new remote actor configured with a Direct router" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" in {

      runOn(master) {
        val actor = system.actorOf(Props[SomeActor], "service-hello")
        actor.isInstanceOf[RemoteActorRef] must be(true)

        val slaveAddress = testConductor.getAddressFor(slave).await
        actor ! "identify"
        expectMsgType[ActorRef].path.address must equal(slaveAddress)

        // shut down the actor before we let the other node(s) shut down so we don't try to send
        // "Terminate" to a shut down node
        system.stop(actor)
      }

      testConductor.enter("done")
    }

    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef (with deployOnAll)" in {

      runOn(master) {
        val actor = system.actorOf(Props[SomeActor], "service-hello2")
        actor.isInstanceOf[RemoteActorRef] must be(true)

        val slaveAddress = testConductor.getAddressFor(slave).await
        actor ! "identify"
        expectMsgType[ActorRef].path.address must equal(slaveAddress)

        // shut down the actor before we let the other node(s) shut down so we don't try to send
        // "Terminate" to a shut down node
        system.stop(actor)
      }

      testConductor.enter("done")
    }
  }
}
