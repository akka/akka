/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import scala.collection.immutable
import akka.testkit._
import akka.routing._
import akka.actor._
import akka.remote.routing._
import com.typesafe.config._
import akka.testkit.TestActors.echoActorProps
import akka.remote.{ RARP, RemoteScope }

object RemoteDeploymentSpec {
  class Echo1 extends Actor {
    var target: ActorRef = context.system.deadLetters

    def receive = {
      case ex: Exception ⇒ throw ex
      case x             ⇒ target = sender(); sender() ! x
    }

    override def preStart() {}
    override def preRestart(cause: Throwable, msg: Option[Any]) {
      target ! "preRestart"
    }
    override def postRestart(cause: Throwable) {}
    override def postStop() {
      target ! "postStop"
    }
  }
}

class RemoteDeploymentSpec extends AkkaSpec("""
    #akka.loglevel=DEBUG
    akka.actor.provider = remote
    akka.remote.artery.enabled = on
    akka.remote.artery.canonical.hostname = localhost
    akka.remote.artery.canonical.port = 0
    """) {

  import RemoteDeploymentSpec._

  val port = RARP(system).provider.getDefaultAddress.port.get
  val conf = ConfigFactory.parseString(
    s"""
    akka.actor.deployment {
      /blub.remote = "akka://${system.name}@localhost:$port"
    }
    """).withFallback(system.settings.config)

  val masterSystem = ActorSystem("Master" + system.name, conf)
  val masterPort = RARP(masterSystem).provider.getDefaultAddress.port.get

  override def afterTermination(): Unit = {
    shutdown(masterSystem)
  }

  "Remoting" must {

    "create and supervise children on remote node" in {
      val senderProbe = TestProbe()(masterSystem)
      val r = masterSystem.actorOf(Props[Echo1], "blub")
      r.path.toString should ===(s"akka://${system.name}@localhost:${port}/remote/akka/${masterSystem.name}@localhost:${masterPort}/user/blub")

      r.tell(42, senderProbe.ref)
      senderProbe.expectMsg(42)
      EventFilter[Exception]("crash", occurrences = 1).intercept {
        r ! new Exception("crash")
      }(masterSystem)
      senderProbe.expectMsg("preRestart")
      r.tell(43, senderProbe.ref)
      senderProbe.expectMsg(43)
      system.stop(r)
      senderProbe.expectMsg("postStop")
    }

  }

}
