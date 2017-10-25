/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote

import java.security.cert.X509Certificate
import javax.net.ssl.{ TrustManager, X509TrustManager }

import akka.actor._
import akka.pattern.ask
import akka.remote.CustomTrustManagerConfig.{ CustomTrustManagerConfig, getConfig }
import akka.remote.transport.netty.CustomTrustManagerFactory
import akka.testkit._
import akka.util.Timeout
import com.typesafe.config._
import org.jboss.netty.util.internal.EmptyArrays

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.reflect.classTag

final class UsageTrustManagerFactory extends CustomTrustManagerFactory {
  override def create(filename: String, password: String): Array[TrustManager] = Array(CustomTrustManagerConfig.tm)
}

object CustomTrustManagerConfig {
  private val trustStore = getClass.getClassLoader.getResource("truststore").getPath
  private val keyStore = getClass.getClassLoader.getResource("keystore").getPath

  import java.util.concurrent.atomic.AtomicInteger

  val usage = new AtomicInteger(0)

  val tm = new X509TrustManager {

    override def checkServerTrusted(x509Certificates: Array[X509Certificate], s: String) = usage.getAndIncrement()

    override def checkClientTrusted(x509Certificates: Array[X509Certificate], s: String) = usage.getAndIncrement()

    override def getAcceptedIssuers = EmptyArrays.EMPTY_X509_CERTIFICATES
  }

  private val conf = s"""
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
          trust-store = "$trustStore"
          key-store = "$keyStore"
          key-store-password = "changeme"
          key-password = "changeme"
          trust-store-password = "changeme"
          protocol = "TLSv1.2"
          trust-manager-factory-class = "akka.remote.UsageTrustManagerFactory"
        }
      }
    }
                     """

  final case class CustomTrustManagerConfig(config: Config, other: Config, usage: AtomicInteger)

  def getConfig(): CustomTrustManagerConfig = {
    val localPort, remotePort = { val s = new java.net.ServerSocket(0); try s.getLocalPort finally s.close() }

    CustomTrustManagerConfig(
      ConfigFactory.parseString(conf.format(localPort)),
      ConfigFactory.parseString(conf.format(remotePort)), usage)
  }

}

class CustomTrustManagerConfigSpec extends CustomTrustManagerConfigCommunicationSpec(getConfig())

abstract class CustomTrustManagerConfigCommunicationSpec(val ctm: CustomTrustManagerConfig) extends AkkaSpec(ctm.config) with ImplicitSender {

  implicit val timeout: Timeout = Timeout(10.seconds)

  lazy val other: ActorSystem = ActorSystem(
    "remote-sys", ctm.other)

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

    "custom trust store usage" in {
      ctm.usage.get shouldBe (2)
    }
  }
}
