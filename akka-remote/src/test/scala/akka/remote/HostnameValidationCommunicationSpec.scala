/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote

import akka.actor._
import akka.pattern.ask
import akka.remote.HostnameConfig.{ HostnameConfig, getConfig }
import akka.testkit._
import akka.util.Timeout
import com.typesafe.config._

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.reflect.classTag

object HostnameConfig {
  // set this in your JAVA_OPTS to see all ssl debug info: "-Djavax.net.debug=ssl,keymanager"
  // The certificate will expire in 2109

  // Key and trust stores with subject alternative names extension for local host variations
  private val trustStore1 = getClass.getClassLoader.getResource("truststore-san-127.0.1.1.jks").getPath
  private val keyStore1 = getClass.getClassLoader.getResource("keystore-san-127.0.1.1.jks").getPath
  private val trustStore2 = getClass.getClassLoader.getResource("truststore-san-127.0.1.2.jks").getPath
  private val keyStore2 = getClass.getClassLoader.getResource("keystore-san-127.0.1.2.jks").getPath
  // To manually verify failure uncomment:
  //  private val trustStore = getClass.getClassLoader.getResource("truststore").getPath
  //  private val keyStore = getClass.getClassLoader.getResource("keystore").getPath
  //  private val trustStore1 = trustStore
  //  private val keyStore1 = keyStore
  //  private val trustStore2 = trustStore
  //  private val keyStore2 = keyStore

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
        hostname = %s
        port = %d
        security {
          trust-store = "%s"
          key-store = "%s"
          key-store-password = "changeme"
          key-password = "changeme"
          trust-store-password = "changeme"
          protocol = "TLSv1.2"
          require-hostname-validation = on
        }
      }
    }
                     """

  final case class HostnameConfig(config: Config, other: Config)

  def getConfig(): HostnameConfig = {
    val localPort, remotePort = { val s = new java.net.ServerSocket(0); try s.getLocalPort finally s.close() }

    HostnameConfig(
      ConfigFactory.parseString(conf.format("127.0.1.1", localPort, trustStore1, keyStore1)),
      ConfigFactory.parseString(conf.format("127.0.1.2", remotePort, trustStore2, keyStore2)))
  }

}

class HostnameValidationSpec extends HostnameValidationCommunicationSpec(getConfig())

abstract class HostnameValidationCommunicationSpec(val cipherConfig: HostnameConfig) extends AkkaSpec(cipherConfig.config) with ImplicitSender {

  implicit val timeout: Timeout = Timeout(10.seconds)

  lazy val other: ActorSystem = ActorSystem(
    "remote-sys", cipherConfig.other)

  override def afterTermination() {
    shutdown(other)
  }

  ("-") must {
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
  }
}
