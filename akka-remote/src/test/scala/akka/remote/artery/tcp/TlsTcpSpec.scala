/**
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.remote.artery
package tcp

import java.security.NoSuchAlgorithmException

import scala.concurrent.duration._

import akka.actor.ActorRef
import akka.actor.ActorPath
import akka.actor.ActorIdentity
import akka.actor.ExtendedActorSystem
import akka.actor.Identify
import akka.actor.RootActorPath
import akka.testkit.ImplicitSender
import akka.testkit.TestActors
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

class TlsTcpWithDefaultConfigSpec extends TlsTcpSpec(ConfigFactory.empty())

class TlsTcpWithSHA1PRNGSpec extends TlsTcpSpec(ConfigFactory.parseString("""
    akka.remote.artery.ssl.config-ssl-engine {
      random-number-generator = "SHA1PRNG"
      enabled-algorithms = ["TLS_RSA_WITH_AES_128_CBC_SHA"]
    }
    """))

class TlsTcpWithAES128CounterSecureRNGSpec extends TlsTcpSpec(ConfigFactory.parseString("""
    akka.remote.artery.ssl.config-ssl-engine {
      random-number-generator = "AES128CounterSecureRNG"
      enabled-algorithms = ["TLS_RSA_WITH_AES_128_CBC_SHA", "TLS_RSA_WITH_AES_256_CBC_SHA"]
    }
    """))

class TlsTcpWithAES256CounterSecureRNGSpec extends TlsTcpSpec(ConfigFactory.parseString("""
    akka.remote.artery.ssl.config-ssl-engine {
      random-number-generator = "AES256CounterSecureRNG"
      enabled-algorithms = ["TLS_RSA_WITH_AES_128_CBC_SHA", "TLS_RSA_WITH_AES_256_CBC_SHA"]
    }
    """))

class TlsTcpWithDefaultRNGSecureSpec extends TlsTcpSpec(ConfigFactory.parseString("""
    akka.remote.artery.ssl.config-ssl-engine {
      random-number-generator = ""
      enabled-algorithms = ["TLS_RSA_WITH_AES_128_CBC_SHA"]
    }
    """))

class TlsTcpWithCrappyRSAWithMD5OnlyHereToMakeSureThingsWorkSpec extends TlsTcpSpec(ConfigFactory.parseString("""
    akka.remote.artery.ssl.config-ssl-engine {
      random-number-generator = ""
      enabled-algorithms = [""SSL_RSA_WITH_NULL_MD5""]
    }
    """))

object TlsTcpSpec {

  lazy val config: Config = {
    ConfigFactory.parseString(s"""
      akka.loglevel = DEBUG
      akka.remote.artery {
        transport = tls-tcp
        large-message-destinations = [ "/user/large" ]
      }
    """)
  }

}

abstract class TlsTcpSpec(config: Config)
  extends ArteryMultiNodeSpec(config.withFallback(TlsTcpSpec.config)) with ImplicitSender {

  val systemB = newRemoteSystem(name = Some("systemB"))
  val addressB = address(systemB)
  val rootB = RootActorPath(addressB)

  def isSupported: Boolean = {
    try {
      val provider = new ConfigSSLEngineProvider(system)

      val rng = provider.createSecureRandom()
      rng.nextInt() // Has to work
      val sRng = provider.SSLRandomNumberGenerator
      if (rng.getAlgorithm != sRng && sRng != "")
        throw new NoSuchAlgorithmException(sRng)

      val address = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
      val host = address.host.get
      val port = address.port.get

      val engine = provider.createServerSSLEngine(host, port)
      val gotAllSupported = provider.SSLEnabledAlgorithms diff engine.getSupportedCipherSuites.toSet
      val gotAllEnabled = provider.SSLEnabledAlgorithms diff engine.getEnabledCipherSuites.toSet
      gotAllSupported.isEmpty || (throw new IllegalArgumentException("Cipher Suite not supported: " + gotAllSupported))
      gotAllEnabled.isEmpty || (throw new IllegalArgumentException("Cipher Suite not enabled: " + gotAllEnabled))
      engine.getSupportedProtocols.contains(provider.SSLProtocol) ||
        (throw new IllegalArgumentException("Protocol not supported: " + provider.SSLProtocol))
    } catch {
      case e @ ((_: IllegalArgumentException) | (_: NoSuchAlgorithmException)) ⇒
        info(e.toString)
        false
    }
  }

  def identify(path: ActorPath): ActorRef = {
    system.actorSelection(path) ! Identify(path.name)
    expectMsgType[ActorIdentity].ref.get
  }

  def testDelivery(echoRef: ActorRef): Unit = {
    echoRef ! "ping-1"
    expectMsg("ping-1")

    // and some more
    (2 to 10).foreach { n ⇒
      echoRef ! s"ping-$n"
    }
    receiveN(9) should equal((2 to 10).map(n ⇒ s"ping-$n"))
  }

  "Artery with TLS/TCP" must {

    if (isSupported) {

      "deliver messages" in {
        systemB.actorOf(TestActors.echoActorProps, "echo")
        val echoRef = identify(rootB / "user" / "echo")
        testDelivery(echoRef)
      }

      "deliver messages over large messages stream" in {
        systemB.actorOf(TestActors.echoActorProps, "large")
        val echoRef = identify(rootB / "user" / "large")
        testDelivery(echoRef)
      }

    } else {
      "not be run when the cipher is not supported by the platform this test is currently being executed on" in {
        pending
      }
    }
  }

}

class TlsTcpWithHostnameVerificationSpec extends ArteryMultiNodeSpec(
  ConfigFactory.parseString("""
    akka.remote.artery.ssl.config-ssl-engine {
      hostname-verification = on
    }
    """).withFallback(TlsTcpSpec.config)) with ImplicitSender {

  val systemB = newRemoteSystem(name = Some("systemB"))
  val addressB = address(systemB)
  val rootB = RootActorPath(addressB)

  "Artery with TLS/TCP and hostname-verification=on" must {
    "reject invalid" in {
      // this test only makes sense with tls-tcp transport
      val arterySettings = ArterySettings(system.settings.config.getConfig("akka.remote.artery"))
      if (!arterySettings.Enabled || arterySettings.Transport != ArterySettings.TlsTcp)
        pending

      systemB.actorOf(TestActors.echoActorProps, "echo")
      system.actorSelection(rootB / "user" / "echo") ! Identify("echo")
      expectNoMessage(2.seconds)
    }
  }
}
