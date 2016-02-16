package akka.stream.impl.io

import javax.net.ssl.SSLContext

import akka.stream._
import akka.stream.impl.StreamLayout.{ CompositeModule, Module }
import akka.stream.TLSProtocol._
import akka.util.ByteString

/**
 * INTERNAL API.
 */
private[akka] final case class TlsModule(plainIn: Inlet[SslTlsOutbound], plainOut: Outlet[SslTlsInbound],
                                         cipherIn: Inlet[ByteString], cipherOut: Outlet[ByteString],
                                         shape: Shape, attributes: Attributes,
                                         sslContext: SSLContext,
                                         firstSession: NegotiateNewSession,
                                         role: TLSRole, closing: TLSClosing, hostInfo: Option[(String, Int)]) extends Module {
  override def subModules: Set[Module] = Set.empty

  override def withAttributes(att: Attributes): Module = copy(attributes = att)
  override def carbonCopy: Module =
    TlsModule(attributes, sslContext, firstSession, role, closing, hostInfo)

  override def replaceShape(s: Shape) =
    if (s != shape) {
      shape.requireSamePortsAs(s)
      CompositeModule(this, s)
    } else this
}

/**
 * INTERNAL API.
 */
private[akka] object TlsModule {
  def apply(attributes: Attributes, sslContext: SSLContext, firstSession: NegotiateNewSession, role: TLSRole, closing: TLSClosing, hostInfo: Option[(String, Int)]): TlsModule = {
    val name = attributes.nameOrDefault(s"StreamTls($role)")
    val cipherIn = Inlet[ByteString](s"$name.cipherIn")
    val cipherOut = Outlet[ByteString](s"$name.cipherOut")
    val plainIn = Inlet[SslTlsOutbound](s"$name.transportIn")
    val plainOut = Outlet[SslTlsInbound](s"$name.transportOut")
    val shape = new BidiShape(plainIn, cipherOut, cipherIn, plainOut)
    TlsModule(plainIn, plainOut, cipherIn, cipherOut, shape, attributes, sslContext, firstSession, role, closing, hostInfo)
  }
}
