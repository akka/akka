/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote

import java.security.NoSuchAlgorithmException

import akka.actor._
import akka.event.NoMarkerLogging
import akka.pattern.ask
import akka.remote.Configuration.{ CipherConfig, getCipherConfig }
import akka.remote.transport.netty.{ NettySSLSupport, SSLSettings }
import akka.testkit._
import akka.util.Timeout
import com.typesafe.config._
import org.uncommons.maths.random.RandomDotOrgSeedGenerator

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.reflect.classTag
import scala.util.control.NonFatal

object Configuration {
  // set this in your JAVA_OPTS to see all ssl debug info: "-Djavax.net.debug=ssl,keymanager"
  // The certificate will expire in 2109
  private val trustStore = getClass.getClassLoader.getResource("truststore").getPath
  private val keyStore = getClass.getClassLoader.getResource("keystore").getPath
  private val conf = """
    akka {
      actor.provider = remote
      test {
        single-expect-default = 10s
        filter-leeway = 10s
        default-timeout = 10s
      }

      remote.enabled-transports = ["akka.remote.netty.ssl"]

      remote.netty.ssl {
        hostname = localhost
        port = %d
        security {
          trust-store = "%s"
          key-store = "%s"
          key-store-password = "changeme"
          key-password = "changeme"
          trust-store-password = "changeme"
          protocol = "TLSv1.2"
          random-number-generator = "%s"
          enabled-algorithms = [%s]
        }
      }
    }
                     """

  final case class CipherConfig(runTest: Boolean, config: Config, cipher: String, localPort: Int, remotePort: Int)

  def getCipherConfig(cipher: String, enabled: String*): CipherConfig = {
    val localPort, remotePort = { val s = new java.net.ServerSocket(0); try s.getLocalPort finally s.close() }
    try {
      //if (true) throw new IllegalArgumentException("Ticket1978*Spec isn't enabled")

      val config = ConfigFactory.parseString(conf.format(localPort, trustStore, keyStore, cipher, enabled.mkString(", ")))
      val fullConfig = config.withFallback(AkkaSpec.testConf).withFallback(ConfigFactory.load).getConfig("akka.remote.netty.ssl.security")
      val settings = new SSLSettings(fullConfig)

      val rng = settings.createSecureRandom(NoMarkerLogging)

      rng.nextInt() // Has to work
      val sRng = settings.SSLRandomNumberGenerator
      rng.getAlgorithm == sRng || (throw new NoSuchAlgorithmException(sRng))

      val engine = NettySSLSupport(settings, NoMarkerLogging, isClient = true).getEngine
      val gotAllSupported = enabled.toSet diff engine.getSupportedCipherSuites.toSet
      val gotAllEnabled = enabled.toSet diff engine.getEnabledCipherSuites.toSet
      gotAllSupported.isEmpty || (throw new IllegalArgumentException("Cipher Suite not supported: " + gotAllSupported))
      gotAllEnabled.isEmpty || (throw new IllegalArgumentException("Cipher Suite not enabled: " + gotAllEnabled))
      engine.getSupportedProtocols.contains(settings.SSLProtocol) ||
        (throw new IllegalArgumentException("Protocol not supported: " + settings.SSLProtocol))

      CipherConfig(true, config, cipher, localPort, remotePort)
    } catch {
      case (_: IllegalArgumentException) | (_: NoSuchAlgorithmException) ⇒ CipherConfig(false, AkkaSpec.testConf, cipher, localPort, remotePort) // Cannot match against the message since the message might be localized :S
    }
  }
}

class Ticket1978SHA1PRNGSpec extends Ticket1978CommunicationSpec(getCipherConfig("SHA1PRNG", "TLS_RSA_WITH_AES_128_CBC_SHA"))

class Ticket1978AES128CounterSecureRNGSpec extends Ticket1978CommunicationSpec(getCipherConfig("AES128CounterSecureRNG", "TLS_RSA_WITH_AES_128_CBC_SHA", "TLS_RSA_WITH_AES_256_CBC_SHA"))

class Ticket1978AES256CounterSecureRNGSpec extends Ticket1978CommunicationSpec(getCipherConfig("AES256CounterSecureRNG", "TLS_RSA_WITH_AES_128_CBC_SHA", "TLS_RSA_WITH_AES_256_CBC_SHA"))

class Ticket1978DefaultRNGSecureSpec extends Ticket1978CommunicationSpec(getCipherConfig("", "TLS_RSA_WITH_AES_128_CBC_SHA"))

class Ticket1978CrappyRSAWithMD5OnlyHereToMakeSureThingsWorkSpec extends Ticket1978CommunicationSpec(getCipherConfig("", "SSL_RSA_WITH_NULL_MD5"))

class Ticket1978NonExistingRNGSecureSpec extends Ticket1978CommunicationSpec(CipherConfig(false, AkkaSpec.testConf, "NonExistingRNG", 12345, 12346))

abstract class Ticket1978CommunicationSpec(val cipherConfig: CipherConfig) extends AkkaSpec(cipherConfig.config) with ImplicitSender {

  implicit val timeout: Timeout = Timeout(10.seconds)

  lazy val other: ActorSystem = ActorSystem(
    "remote-sys",
    ConfigFactory.parseString("akka.remote.netty.ssl.port = " + cipherConfig.remotePort).withFallback(system.settings.config))

  override def afterTermination() {
    if (cipherConfig.runTest) {
      shutdown(other)
    }
  }

  def preCondition: Boolean = true

  ("-") must {
    if (cipherConfig.runTest && preCondition) {
      val ignoreMe = other.actorOf(Props(new Actor { def receive = { case ("ping", x) ⇒ sender() ! ((("pong", x), sender())) } }), "echo")
      val otherAddress = other.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider].transport.defaultAddress

      "support tell" in within(timeout.duration) {
        val here = {
          system.actorSelection(otherAddress.toString + "/user/echo") ! Identify(None)
          expectMsgType[ActorIdentity].ref.get
        }

        for (i ← 1 to 1000) here ! (("ping", i))
        for (i ← 1 to 1000) expectMsgPF() { case (("pong", i), `testActor`) ⇒ true }
      }

      "support ask" in within(timeout.duration) {
        import system.dispatcher
        val here = {
          system.actorSelection(otherAddress.toString + "/user/echo") ! Identify(None)
          expectMsgType[ActorIdentity].ref.get
        }

        val f = for (i ← 1 to 1000) yield here ? (("ping", i)) mapTo classTag[((String, Int), ActorRef)]
        Await.result(Future.sequence(f), remaining).map(_._1._1).toSet should ===(Set("pong"))
      }

    } else {
      "not be run when the cipher is not supported by the platform this test is currently being executed on" in {
        pending
      }
    }

  }

}
