/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.pattern.ask
import testkit.{ STMultiNodeSpec, MultiNodeConfig, MultiNodeSpec }
import akka.testkit._
import akka.actor.Identify
import akka.actor.ActorIdentity

object LookupRemoteActorMultiJvmSpec extends MultiNodeConfig {

  class SomeActor extends Actor {
    def receive = {
      case "identify" ⇒ sender() ! self
    }
  }

  commonConfig(debugConfig(on = false))

  val master = role("master")
  val slave = role("slave")

}

class LookupRemoteActorMultiJvmNode1 extends LookupRemoteActorSpec
class LookupRemoteActorMultiJvmNode2 extends LookupRemoteActorSpec

class LookupRemoteActorSpec extends MultiNodeSpec(LookupRemoteActorMultiJvmSpec)
  with STMultiNodeSpec with ImplicitSender with DefaultTimeout {
  import LookupRemoteActorMultiJvmSpec._

  def initialParticipants = 2

  runOn(master) {
    system.actorOf(Props[SomeActor], "service-hello")
  }

  "Remoting" must {
    "lookup remote actor" taggedAs LongRunningTest in {
      runOn(slave) {
        val hello = {
          system.actorSelection(node(master) / "user" / "service-hello") ! Identify("id1")
          expectMsgType[ActorIdentity].ref.get
        }
        hello.isInstanceOf[RemoteActorRef] should ===(true)
        val masterAddress = testConductor.getAddressFor(master).await
        (hello ? "identify").await.asInstanceOf[ActorRef].path.address should ===(masterAddress)
      }
      enterBarrier("done")
    }
  }

}

