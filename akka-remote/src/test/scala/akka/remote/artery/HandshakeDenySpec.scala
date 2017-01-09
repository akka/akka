/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import scala.concurrent.duration._

import akka.actor.{ ActorIdentity, ActorSystem, Identify }
import akka.testkit._
import com.typesafe.config.ConfigFactory
import akka.actor.RootActorPath

object HandshakeDenySpec {

  val commonConfig = ConfigFactory.parseString(s"""
     akka.loglevel = WARNING
     akka.remote.artery.advanced.handshake-timeout = 2s
     akka.remote.artery.advanced.image-liveness-timeout = 1.9s
  """).withFallback(ArterySpecSupport.defaultConfig)

}

class HandshakeDenySpec extends ArteryMultiNodeSpec(HandshakeDenySpec.commonConfig) with ImplicitSender {
  import HandshakeDenySpec._

  var systemB = newRemoteSystem(name = Some("systemB"))

  "Artery handshake" must {

    "be denied when originating address is unknown" in {
      val sel = system.actorSelection(RootActorPath(address(systemB).copy(host = Some("127.0.0.1"))) / "user" / "echo")

      systemB.actorOf(TestActors.echoActorProps, "echo")

      EventFilter.warning(start = "Dropping Handshake Request from").intercept {
        sel ! Identify(None)
        expectNoMsg(3.seconds)
      }(systemB)
    }

  }

}
