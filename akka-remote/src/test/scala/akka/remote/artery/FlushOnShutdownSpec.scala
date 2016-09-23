/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.actor.{ Actor, ActorIdentity, ActorSystem, Identify, Props, RootActorPath }
import akka.remote.RARP
import akka.testkit.{ AkkaSpec, ImplicitSender, TestProbe }
import com.typesafe.config.ConfigFactory

import scala.concurrent.Await
import scala.concurrent.duration._

object FlushOnShutdownSpec {

  val config = ConfigFactory.parseString(s"""
     akka {
       actor.provider = remote
       actor.serialize-creators = off
       remote.artery.enabled = on
       remote.artery.canonical.hostname = localhost
       remote.artery.canonical.port = 0
     }
  """)

}

class FlushOnShutdownSpec extends ArteryMultiNodeSpec(FlushOnShutdownSpec.config) {

  val remoteSystem = newRemoteSystem()

  "Artery" must {

    "flush messages enqueued before shutdown" in {

      val probe = TestProbe()
      val probeRef = probe.ref

      localSystem.actorOf(Props(new Actor {
        def receive = {
          case msg ⇒ probeRef ! msg
        }
      }), "receiver")

      val actorOnSystemB = remoteSystem.actorOf(Props(new Actor {
        def receive = {
          case "start" ⇒
            context.actorSelection(rootActorPath(localSystem) / "user" / "receiver") ! Identify(None)

          case ActorIdentity(_, Some(receiverRef)) ⇒
            receiverRef ! "msg1"
            receiverRef ! "msg2"
            receiverRef ! "msg3"
            context.system.terminate()
        }
      }), "sender")

      actorOnSystemB ! "start"

      probe.expectMsg("msg1")
      probe.expectMsg("msg2")
      probe.expectMsg("msg3")

      Await.result(remoteSystem.whenTerminated, 6.seconds)
    }

  }

}
