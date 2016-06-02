package akka.stream.impl.io

import javax.net.ssl.SSLContext

import akka.stream._
import akka.stream.impl.StreamLayout.{ CompositeModule, AtomicModule }
import akka.stream.TLSProtocol._
import akka.util.ByteString
import com.typesafe.sslconfig.akka.AkkaSSLConfig

/**
 * INTERNAL API.
 */
private[akka] final case class TlsModule(plainIn: Inlet[SslTlsOutbound], plainOut: Outlet[SslTlsInbound],
                                         cipherIn: Inlet[ByteString], cipherOut: Outlet[ByteString],
                                         shape: Shape, attributes: Attributes,
                                         sslContext:   SSLContext,
                                         sslConfig:    Option[AkkaSSLConfig],
                                         firstSession: NegotiateNewSession,
                                         role:         TLSRole, closing: TLSClosing, hostInfo: Option[(String, Int)]) extends AtomicModule {

  override def withAttributes(att: Attributes): TlsModule = copy(attributes = att)
  override def carbonCopy: TlsModule =
    TlsModule(attributes, sslContext, sslConfig, firstSession, role, closing, hostInfo)

  override def replaceShape(s: Shape) =
    if (s != shape) {
      shape.requireSamePortsAs(s)
      CompositeModule(this, s)
    } else this

  override def toString: String = f"TlsModule($firstSession, $role, $closing, $hostInfo) [${System.identityHashCode(this)}%08x]"
}

/**
 * INTERNAL API.
 */
private[akka] object TlsModule {
  def apply(attributes: Attributes, sslContext: SSLContext, sslConfig: Option[AkkaSSLConfig], firstSession: NegotiateNewSession, role: TLSRole, closing: TLSClosing, hostInfo: Option[(String, Int)]): TlsModule = {
    val name = attributes.nameOrDefault(s"StreamTls($role)")
    val cipherIn = Inlet[ByteString](s"$name.cipherIn")
    val cipherOut = Outlet[ByteString](s"$name.cipherOut")
    val plainIn = Inlet[SslTlsOutbound](s"$name.transportIn")
    val plainOut = Outlet[SslTlsInbound](s"$name.transportOut")
    val shape = new BidiShape(plainIn, cipherOut, cipherIn, plainOut)
    TlsModule(plainIn, plainOut, cipherIn, cipherOut, shape, attributes, sslContext, sslConfig, firstSession, role, closing, hostInfo)
  }
}
