package akka.stream.io

import java.security.{ SecureRandom, KeyStore }
import javax.net.ssl.{ SSLContext, TrustManagerFactory, KeyManagerFactory }

import akka.stream.ActorMaterializer
import akka.stream.io.TlsSpec._
import akka.stream.scaladsl._
import akka.stream.testkit.AkkaSpec
import akka.util.ByteString

import scala.concurrent.Await
import scala.concurrent.duration._

object NewTlsSpec {
  def initWithTrust(trustPath: String) = {
    val password = "changeme"

    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
    keyStore.load(getClass.getResourceAsStream("/keystore"), password.toCharArray)

    val trustStore = KeyStore.getInstance(KeyStore.getDefaultType)
    trustStore.load(getClass.getResourceAsStream(trustPath), password.toCharArray)

    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keyManagerFactory.init(keyStore, password.toCharArray)

    val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trustManagerFactory.init(trustStore)

    val context = SSLContext.getInstance("TLS")
    context.init(keyManagerFactory.getKeyManagers, trustManagerFactory.getTrustManagers, new SecureRandom)
    context
  }

  def initSslContext(): SSLContext = initWithTrust("/truststore")
}

class NewTlsSpec extends AkkaSpec("akka.loglevel=DEBUG") {

  implicit val materializer = ActorMaterializer()

  val sslContext = initSslContext()

  val cipherSuites = NegotiateNewSession.withCipherSuites("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA", "TLS_RSA_WITH_AES_128_CBC_SHA")

  val serverTls = BidiFlow.fromGraph(
    new SslTlsNew(sslContext, cipherSuites, Server, Closing.eagerClose, hostInfo = None, true))

  val clientTls = BidiFlow.fromGraph(
    new SslTlsNew(sslContext, cipherSuites, Client, Closing.eagerClose, hostInfo = None, true))

  val tlsEcho = Flow[SslTlsInbound].map { s ⇒ SendBytes(s.asInstanceOf[SessionBytes].bytes) }

  "TLS implementation" must {

    "work in the happy case" in {
      val srv = serverTls.reversed.join(tlsEcho)
      val result =
        Source.single(ByteString("Hello world!"))
          .map(SendBytes)
          .map { x ⇒ println("SND" + x); x }
          .via(clientTls join srv)
          .map(_.asInstanceOf[SessionBytes].bytes)
          .map { x ⇒ println("RCV" + x); x }
          .runFold(ByteString.empty)(_ ++ _)

      Await.result(result, 3.seconds)
    }

  }

}
