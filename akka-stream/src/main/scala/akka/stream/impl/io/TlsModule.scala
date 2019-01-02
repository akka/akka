/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.io

import javax.net.ssl.{ SSLEngine, SSLSession }

import akka.NotUsed
import akka.actor.ActorSystem
import akka.annotation.InternalApi
import akka.stream._
import akka.stream.impl.StreamLayout.AtomicModule
import akka.stream.TLSProtocol._
import akka.stream.impl.{ TlsModuleIslandTag, TraversalBuilder }
import akka.util.ByteString

import scala.util.Try

/**
 * INTERNAL API.
 */
@InternalApi private[stream] final case class TlsModule(plainIn: Inlet[SslTlsOutbound], plainOut: Outlet[SslTlsInbound],
                                                        cipherIn: Inlet[ByteString], cipherOut: Outlet[ByteString],
                                                        shape:           BidiShape[SslTlsOutbound, ByteString, ByteString, SslTlsInbound],
                                                        attributes:      Attributes,
                                                        createSSLEngine: ActorSystem ⇒ SSLEngine, // ActorSystem is only needed to support the AkkaSSLConfig legacy, see #21753
                                                        verifySession:   (ActorSystem, SSLSession) ⇒ Try[Unit], // ActorSystem is only needed to support the AkkaSSLConfig legacy, see #21753
                                                        closing:         TLSClosing)
  extends AtomicModule[BidiShape[SslTlsOutbound, ByteString, ByteString, SslTlsInbound], NotUsed] {

  override def withAttributes(att: Attributes): TlsModule = copy(attributes = att)

  override def toString: String = f"TlsModule($closing) [${System.identityHashCode(this)}%08x]"

  override private[stream] def traversalBuilder = TraversalBuilder.atomic(this, attributes).makeIsland(TlsModuleIslandTag)
}

/**
 * INTERNAL API.
 */
@InternalApi private[stream] object TlsModule {
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
