/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl

import java.net.InetSocketAddress
import akka.actor.ActorSystem
import akka.annotation.ApiMayChange
import akka.http.impl.engine.client.HttpsProxyGraphStage
import akka.http.scaladsl.Http.OutgoingConnection
import akka.http.scaladsl.model.headers.HttpCredentials
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.stream.scaladsl.{ Flow, Keep, Tcp }
import akka.util.ByteString
import scala.concurrent.Future

/**
 * Abstraction to allow the creation of alternative transports to run HTTP on.
 *
 * (Still unstable) SPI for implementors of custom client transports.
 */
// #client-transport-definition
@ApiMayChange
trait ClientTransport {
  def connectTo(host: String, port: Int, settings: ClientConnectionSettings)(implicit system: ActorSystem): Flow[ByteString, ByteString, Future[OutgoingConnection]]
}
// #client-transport-definition

/**
 * (Still unstable) entry point to create or access predefined client transports.
 */
@ApiMayChange
object ClientTransport {
  val TCP: ClientTransport = TCPTransport

  private case object TCPTransport extends ClientTransport {
    def connectTo(host: String, port: Int, settings: ClientConnectionSettings)(implicit system: ActorSystem): Flow[ByteString, ByteString, Future[OutgoingConnection]] =
      // The InetSocketAddress representing the remote address must be created unresolved because akka.io.TcpOutgoingConnection will
      // not attempt DNS resolution if the InetSocketAddress is already resolved. That behavior is problematic when it comes to
      // connection pools since it means that new connections opened by the pool in the future can end up using a stale IP address.
      // By passing an unresolved InetSocketAddress instead, we ensure that DNS resolution is performed for every new connection.
      Tcp().outgoingConnection(InetSocketAddress.createUnresolved(host, port), settings.localAddress,
        settings.socketOptions, halfClose = true, settings.connectingTimeout, settings.idleTimeout)
        .mapMaterializedValue(_.map(tcpConn ⇒ OutgoingConnection(tcpConn.localAddress, tcpConn.remoteAddress))(system.dispatcher))
  }

  /**
   * Returns a [[ClientTransport]] that runs all connection through the given HTTP(S) proxy using the
   * HTTP CONNECT method.
   *
   * An HTTP(S) proxy is a proxy that will create one TCP connection to the HTTP(S) proxy for each target connection. The
   * proxy transparently forwards the TCP connection to the target host.
   *
   * For more information about HTTP CONNECT tunnelling see https://tools.ietf.org/html/rfc7231#section-4.3.6.
   */
  def httpsProxy(proxyAddress: InetSocketAddress): ClientTransport =
    HttpsProxyTransport(proxyAddress)

  /**
   * Returns a [[ClientTransport]] that runs all connection through the given HTTP(S) proxy using the
   * HTTP CONNECT method. This method also takes [[HttpCredentials]] in order to pass along to the proxy.
   *
   * An HTTP(S) proxy is a proxy that will create one TCP connection to the HTTP(S) proxy for each target connection. The
   * proxy transparently forwards the TCP connection to the target host.
   *
   * For more information about HTTP CONNECT tunnelling see https://tools.ietf.org/html/rfc7231#section-4.3.6.
   */
  def httpsProxy(proxyAddress: InetSocketAddress, proxyCredentials: HttpCredentials): ClientTransport =
    HttpsProxyTransport(proxyAddress, proxyCredentials = Some(proxyCredentials))

  private case class HttpsProxyTransport(proxyAddress: InetSocketAddress, underlyingTransport: ClientTransport = TCP, proxyCredentials: Option[HttpCredentials] = None) extends ClientTransport {
    def this(proxyAddress: InetSocketAddress, underlyingTransport: ClientTransport) = this(proxyAddress, underlyingTransport, None)

    def connectTo(host: String, port: Int, settings: ClientConnectionSettings)(implicit system: ActorSystem): Flow[ByteString, ByteString, Future[OutgoingConnection]] =
      HttpsProxyGraphStage(host, port, settings, proxyCredentials)
        .joinMat(underlyingTransport.connectTo(proxyAddress.getHostString, proxyAddress.getPort, settings))(Keep.right)
        // on the HTTP level we want to see the final remote address in the `OutgoingConnection`
        .mapMaterializedValue(_.map(_.copy(remoteAddress = InetSocketAddress.createUnresolved(host, port)))(system.dispatcher))
  }
}
