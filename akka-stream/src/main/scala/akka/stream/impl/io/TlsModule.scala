package akka.stream.impl.io

import javax.net.ssl.{ SSLContext, SSLEngine, SSLSession }

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.impl.StreamLayout.{ AtomicModule, CompositeModule }
import akka.stream.TLSProtocol._
import akka.util.ByteString
import com.typesafe.sslconfig.akka.AkkaSSLConfig

import scala.util.Try

/**
 * INTERNAL API.
 */
private[stream] final case class TlsModule(plainIn: Inlet[SslTlsOutbound], plainOut: Outlet[SslTlsInbound],
                                           cipherIn: Inlet[ByteString], cipherOut: Outlet[ByteString],
                                           shape: Shape, attributes: Attributes,
                                           createSSLEngine: ActorSystem ⇒ SSLEngine, // ActorSystem is only needed to support the AkkaSSLConfig legacy, see #21753
                                           verifySession:   (ActorSystem, SSLSession) ⇒ Try[Unit], // ActorSystem is only needed to support the AkkaSSLConfig legacy, see #21753
                                           closing:         TLSClosing) extends AtomicModule {

  override def withAttributes(att: Attributes): TlsModule = copy(attributes = att)
  override def carbonCopy: TlsModule = TlsModule(attributes, createSSLEngine, verifySession, closing)

  override def replaceShape(s: Shape) =
    if (s != shape) {
      shape.requireSamePortsAs(s)
      CompositeModule(this, s)
    } else this

  override def toString: String = f"TlsModule($closing) [${System.identityHashCode(this)}%08x]"
}

/**
 * INTERNAL API.
 */
private[stream] object TlsModule {
  def apply(
    attributes:      Attributes,
    createSSLEngine: ActorSystem ⇒ SSLEngine, // ActorSystem is only needed to support the AkkaSSLConfig legacy, see #21753
    verifySession:   (ActorSystem, SSLSession) ⇒ Try[Unit], // ActorSystem is only needed to support the AkkaSSLConfig legacy, see #21753
    closing:         TLSClosing): TlsModule = {
    val name = attributes.nameOrDefault(s"StreamTls()")
    val cipherIn = Inlet[ByteString](s"$name.cipherIn")
    val cipherOut = Outlet[ByteString](s"$name.cipherOut")
    val plainIn = Inlet[SslTlsOutbound](s"$name.transportIn")
    val plainOut = Outlet[SslTlsInbound](s"$name.transportOut")
    val shape = new BidiShape(plainIn, cipherOut, cipherIn, plainOut)
    TlsModule(plainIn, plainOut, cipherIn, cipherOut, shape, attributes, createSSLEngine, verifySession, closing)
  }
}
