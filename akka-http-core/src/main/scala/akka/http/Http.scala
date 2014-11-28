/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import java.io.Closeable
import java.net.InetSocketAddress
import akka.stream.io.StreamTcp
import akka.stream.scaladsl._
import scala.collection.immutable
import akka.io.Inet
import akka.http.engine.client.{ HttpClientPipeline, ClientConnectionSettings }
import akka.http.engine.server.{ HttpServerPipeline, ServerSettings }
import akka.http.model.{ ErrorInfo, HttpResponse, HttpRequest }
import akka.actor._

import scala.concurrent.Future

object Http extends ExtensionKey[HttpExt] with ExtensionIdProvider {

  /**
   * A flow representing an outgoing HTTP connection, and the key used to get information about
   * the materialized connection. The flow takes pairs of a ``HttpRequest`` and a user definable
   * context that will be correlated with the corresponding ``HttpResponse``.
   */
  final case class OutgoingFlow(flow: Flow[(HttpRequest, Any), (HttpResponse, Any)],
                                key: Key { type MaterializedType = Future[Http.OutgoingConnection] })

  /**
   * The materialized result of an outgoing HTTP connection stream with a single connection as the underlying transport.
   */
  final case class OutgoingConnection(remoteAddress: InetSocketAddress,
                                      localAddress: InetSocketAddress)

  /**
   * A source representing an bound HTTP server socket, and the key to get information about
   * the materialized bound socket.
   */
  final case class ServerSource(source: Source[IncomingConnection],
                                key: Key { type MaterializedType = Future[ServerBinding] })

  /**
   * An incoming HTTP connection.
   */
  final case class IncomingConnection(remoteAddress: InetSocketAddress, stream: Flow[HttpResponse, HttpRequest])

  class StreamException(val info: ErrorInfo) extends RuntimeException(info.summary)

  /**
   * The materialized result of a bound HTTP server socket.
   */
  private[akka] sealed abstract case class ServerBinding(localAddress: InetSocketAddress) extends Closeable

  /**
   * INTERNAL API
   */
  private[akka] object ServerBinding {
    def apply(localAddress: InetSocketAddress, closeable: Closeable): ServerBinding =
      new ServerBinding(localAddress) {
        override def close() = closeable.close()
      }
  }
}

class HttpExt(system: ExtendedActorSystem) extends Extension {
  @volatile private[this] var clientPipelines = Map.empty[ClientConnectionSettings, HttpClientPipeline]

  def connect(remoteAddress: InetSocketAddress,
              localAddress: Option[InetSocketAddress],
              options: immutable.Traversable[Inet.SocketOption],
              settings: Option[ClientConnectionSettings]): Http.OutgoingFlow = {
    // FIXME #16378 Where to do logging? log.debug("Attempting connection to {}", remoteAddress)
    val effectiveSettings = ClientConnectionSettings(settings)(system)

    val tcpFlow = StreamTcp(system).connect(remoteAddress, localAddress, options, effectiveSettings.connectingTimeout)
    val pipeline = clientPipelines.getOrElse(effectiveSettings, {
      val pl = new HttpClientPipeline(effectiveSettings, system.log)(system.dispatcher)
      clientPipelines = clientPipelines.updated(effectiveSettings, pl)
      pl
    })
    pipeline(tcpFlow, remoteAddress)
  }

  def connect(host: String, port: Int = 80,
              localAddress: Option[InetSocketAddress] = None,
              options: immutable.Traversable[Inet.SocketOption] = Nil,
              settings: Option[ClientConnectionSettings] = None): Http.OutgoingFlow =
    connect(new InetSocketAddress(host, port), localAddress, options, settings)

  def bind(endpoint: InetSocketAddress,
           backlog: Int,
           options: immutable.Traversable[Inet.SocketOption],
           serverSettings: Option[ServerSettings]): Http.ServerSource = {
    import system.dispatcher

    // FIXME IdleTimeout?
    val src = StreamTcp(system).bind(endpoint, backlog, options)
    val key = new Key {
      override type MaterializedType = Future[Http.ServerBinding]
      override def materialize(map: MaterializedMap) = map.get(src).map(s ⇒ Http.ServerBinding(s.localAddress, s))
    }
    val log = system.log
    val effectiveSettings = ServerSettings(serverSettings)(system)
    Http.ServerSource(src.withKey(key).map(new HttpServerPipeline(effectiveSettings, log)), key)
  }

  def bind(interface: String, port: Int = 80, backlog: Int = 100,
           options: immutable.Traversable[Inet.SocketOption] = Nil,
           serverSettings: Option[ServerSettings] = None): Http.ServerSource =
    bind(new InetSocketAddress(interface, port), backlog, options, serverSettings)
}
