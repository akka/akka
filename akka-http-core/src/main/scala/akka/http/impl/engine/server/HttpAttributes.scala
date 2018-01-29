/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.server

import java.net.InetSocketAddress
import javax.net.ssl.SSLSession

import akka.annotation.InternalApi
import akka.stream.Attributes

/**
 * INTERNAL API
 * Internally used attributes set in the HTTP pipeline.
 * May potentially be opened up in the future.
 */
@InternalApi
private[akka] object HttpAttributes {
  import Attributes._

  private[akka] final case class RemoteAddress(address: InetSocketAddress) extends Attribute

  private[akka] def remoteAddress(address: Option[InetSocketAddress]): Attributes =
    address match {
      case Some(addr) ⇒ remoteAddress(addr)
      case None       ⇒ empty
    }

  private[akka] def remoteAddress(address: InetSocketAddress): Attributes =
    Attributes(RemoteAddress(address))

  /**
   * INTERNAL API
   * Internally used TLS session info in the HTTP pipeline.
   */
  @InternalApi
  private[akka] final case class TLSSessionInfo(session: SSLSession) extends Attribute

  private[akka] def tlsSessionInfo(session: SSLSession): Attributes =
    Attributes(TLSSessionInfo(session))

  private[akka] val empty: Attributes =
    Attributes()
}
