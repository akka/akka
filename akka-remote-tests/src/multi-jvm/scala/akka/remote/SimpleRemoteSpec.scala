/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.pattern.ask
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._

object SimpleRemoteMultiJvmSpec extends MultiNodeConfig {

  class SomeActor extends Actor with Serializable {
    def receive = {
      case "identify" ⇒ sender ! self
    }
  }

  commonConfig(debugConfig(on = false))

  val master = role("master")
  val slave = role("slave")

}

class SimpleRemoteMultiJvmNode1 extends SimpleRemoteSpec
class SimpleRemoteMultiJvmNode2 extends SimpleRemoteSpec

class SimpleRemoteSpec extends MultiNodeSpec(SimpleRemoteMultiJvmSpec)
  with ImplicitSender with DefaultTimeout {
  import SimpleRemoteMultiJvmSpec._

  def initialParticipants = 2

  runOn(master) {
    system.actorOf(Props[SomeActor], "service-hello")
  }

  "Remoting" must {
    "lookup remote actor" in {
      runOn(slave) {
        val hello = system.actorFor(node(master) / "user" / "service-hello")
        hello.isInstanceOf[RemoteActorRef] must be(true)
        val masterAddress = testConductor.getAddressFor(master).await
        (hello ? "identify").await.asInstanceOf[ActorRef].path.address must equal(masterAddress)
      }
      testConductor.enter("done")
    }
  }

}

