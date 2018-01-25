/**
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.remote.artery

import scala.concurrent.duration._

import akka.actor.ActorIdentity
import akka.actor.Identify
import akka.actor.RootActorPath
import akka.testkit.ImplicitSender
import akka.testkit.TestActors
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

class TlsTcpWithDefaultConfigSpec extends TlsTcpSpec(ConfigFactory.parseString("""
    akka.remote.artery {
      transport = tls-tcp
    }
    """).withFallback(ArterySpecSupport.defaultConfig))

// FIXME test more config combinations, see Ticket1978CommunicationSpec

object TlsTcpSpec {

  def keystoreConfig(): Config = {
    val trustStore = getClass.getClassLoader.getResource("truststore").getPath
    val keyStore = getClass.getClassLoader.getResource("keystore").getPath

    ConfigFactory.parseString(s"""
      akka.remote.artery.ssl {
          key-store = "$keyStore"
          trust-store = "$trustStore"
      }
    """)
  }

}

abstract class TlsTcpSpec(config: Config)
  extends ArteryMultiNodeSpec(config.withFallback(TlsTcpSpec.keystoreConfig())) with ImplicitSender {

  val systemB = newRemoteSystem(name = Some("systemB"))
  val addressB = address(systemB)
  val rootB = RootActorPath(addressB)

  "Artery with TLS/TCP" must {

    "deliver messages" in {
      systemB.actorOf(TestActors.echoActorProps, "echo")

      val echoRef = {
        system.actorSelection(rootB / "user" / "echo") ! Identify(None)
        expectMsgType[ActorIdentity](5.seconds).ref.get
      }

      echoRef ! "ping-1"
      expectMsg("ping-1")

      // and some more
      (2 to 10).foreach { n ⇒
        echoRef ! s"ping-$n"
      }
      receiveN(9) should equal((2 to 10).map(n ⇒ s"ping-$n"))
    }

    "deliver messages over large messages stream" in {
      // FIXME
      pending
    }
  }

}
