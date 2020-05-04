/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import com.typesafe.config.ConfigFactory
import testkit.MultiNodeConfig

import akka.actor.Actor
import akka.actor.ActorIdentity
import akka.actor.ActorRef
import akka.actor.Identify
import akka.actor.Props
import akka.pattern.ask
import akka.testkit._

class LookupRemoteActorMultiJvmSpec(artery: Boolean) extends MultiNodeConfig {

  commonConfig(debugConfig(on = false).withFallback(ConfigFactory.parseString(s"""
      akka.remote.artery.enabled = $artery
      """)).withFallback(RemotingMultiNodeSpec.commonConfig))

  val master = role("master")
  val slave = role("slave")

}

class LookupRemoteActorMultiJvmNode1 extends LookupRemoteActorSpec(new LookupRemoteActorMultiJvmSpec(artery = false))
class LookupRemoteActorMultiJvmNode2 extends LookupRemoteActorSpec(new LookupRemoteActorMultiJvmSpec(artery = false))

class ArteryLookupRemoteActorMultiJvmNode1
    extends LookupRemoteActorSpec(new LookupRemoteActorMultiJvmSpec(artery = true))
class ArteryLookupRemoteActorMultiJvmNode2
    extends LookupRemoteActorSpec(new LookupRemoteActorMultiJvmSpec(artery = true))

object LookupRemoteActorSpec {
  class SomeActor extends Actor {
    def receive = {
      case "identify" => sender() ! self
    }
  }
}

abstract class LookupRemoteActorSpec(multiNodeConfig: LookupRemoteActorMultiJvmSpec)
    extends RemotingMultiNodeSpec(multiNodeConfig) {
  import LookupRemoteActorSpec._
  import multiNodeConfig._

  def initialParticipants = 2

  runOn(master) {
    system.actorOf(Props[SomeActor](), "service-hello")
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
