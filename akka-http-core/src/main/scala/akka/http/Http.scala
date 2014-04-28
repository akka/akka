/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import java.net.InetSocketAddress
import com.typesafe.config.Config
import org.reactivestreams.api.{ Producer, Consumer }
import scala.concurrent.duration.Duration
import scala.collection.immutable
import akka.io.{ Inet, Tcp }
import akka.stream.MaterializerSettings
import akka.http.client.{ HostConnectorSettings, ClientConnectionSettings }
import akka.http.server.ServerSettings
import akka.http.model.{ HttpResponse, HttpRequest, HttpHeader }
import akka.http.util._
import akka.actor._

object Http extends ExtensionKey[HttpExt] {

  /**
   * Command that can be sent to `IO(Http)` to trigger the setup of an HTTP client facility at
   * a certain API level (connection, host or request).
   * The HTTP layer will respond with an `OutgoingHttpChannelInfo` reply (or `Status.Failure`).
   * The sender `ActorRef`of this response can then be sent `HttpRequest` instances to which
   * it will respond with `HttpResponse` instances (or `Status.Failure`).
   */
  sealed trait OutgoingHttpChannelSetup

  case class Connect(remoteAddress: InetSocketAddress,
                     sslEncryption: Boolean,
                     localAddress: Option[InetSocketAddress],
                     options: immutable.Traversable[Inet.SocketOption],
                     settings: Option[ClientConnectionSettings]) extends OutgoingHttpChannelSetup
  object Connect {
    def apply(host: String, port: Int = 80, sslEncryption: Boolean = false, localAddress: Option[InetSocketAddress] = None,
              options: immutable.Traversable[Inet.SocketOption] = Nil, settings: Option[ClientConnectionSettings] = None): Connect =
      apply(new InetSocketAddress(host, port), sslEncryption, localAddress, options, settings)
  }

  case class HostConnectorSetup(host: String, port: Int = 80,
                                sslEncryption: Boolean = false,
                                options: immutable.Traversable[Inet.SocketOption] = Nil,
                                settings: Option[HostConnectorSettings] = None,
                                connectionType: ClientConnectionType = ClientConnectionType.AutoProxied,
                                defaultHeaders: immutable.Seq[HttpHeader] = Nil) extends OutgoingHttpChannelSetup {
    private[http] def normalized(implicit refFactory: ActorRefFactory) =
      if (settings.isDefined) this
      else copy(settings = Some(HostConnectorSettings(actorSystem)))
  }
  object HostConnectorSetup {
    def apply(host: String, port: Int, sslEncryption: Boolean)(implicit refFactory: ActorRefFactory): HostConnectorSetup =
      apply(host, port, sslEncryption).normalized
  }

  case object HttpRequestChannelSetup extends OutgoingHttpChannelSetup

  case class OpenOutgoingHttpChannel(channelSetup: OutgoingHttpChannelSetup)

  /**
   * Command triggering the shutdown of the respective HTTP channel.
   * If sent to
   * - client-side connection actors: triggers the closing of the connection
   * - host-connector actors: triggers the closing of all connections and the shutdown of the host-connector
   * - the `HttpManager` actor: triggers the closing of all outgoing and incoming connections, the shutdown of all
   *   host-connectors and the unbinding of all servers
   */
  type CloseCommand = Tcp.CloseCommand
  val Close = Tcp.Close
  val ConfirmedClose = Tcp.ConfirmedClose
  val Abort = Tcp.Abort

  sealed trait ClientConnectionType
  object ClientConnectionType {
    object Direct extends ClientConnectionType
    object AutoProxied extends ClientConnectionType
    case class Proxied(proxyHost: String, proxyPort: Int) extends ClientConnectionType
  }

  case class Bind(endpoint: InetSocketAddress,
                  backlog: Int,
                  options: immutable.Traversable[Inet.SocketOption],
                  serverSettings: Option[ServerSettings],
                  materializerSettings: MaterializerSettings)
  object Bind {
    def apply(interface: String, port: Int = 80, backlog: Int = 100,
              options: immutable.Traversable[Inet.SocketOption] = Nil,
              serverSettings: Option[ServerSettings] = None,
              materializerSettings: MaterializerSettings = MaterializerSettings()): Bind =
      apply(new InetSocketAddress(interface, port), backlog, options, serverSettings, materializerSettings)
  }

  case class Unbind(timeout: Duration)
  object Unbind extends Unbind(Duration.Zero)

  type ConnectionClosed = Tcp.ConnectionClosed
  val Closed = Tcp.Closed
  val Aborted = Tcp.Aborted
  val ConfirmedClosed = Tcp.ConfirmedClosed
  val PeerClosed = Tcp.PeerClosed
  type ErrorClosed = Tcp.ErrorClosed; val ErrorClosed = Tcp.ErrorClosed

  /**
   * Response to an `OutgoingHttpChannelSetup` command (in the success case).
   * The `transport` actor can be sent `HttpRequest` instances which will be responded
   * to with the respective `HttpResponse` instance as soon as the start of which has
   * been received.
   * The sender of the `OutgoingHttpChannelInfo` response is always identical to the transport.
   */
  sealed trait OutgoingHttpChannelInfo {
    def transport: ActorRef
  }

  case class Connected(transport: ActorRef,
                       remoteAddress: InetSocketAddress,
                       localAddress: InetSocketAddress) extends OutgoingHttpChannelInfo

  case class HostConnectorInfo(transport: ActorRef,
                               setup: HostConnectorSetup) extends OutgoingHttpChannelInfo

  case class HttpRequestChannelInfo(transport: ActorRef) extends OutgoingHttpChannelInfo

  ///////////////////// server-side events ////////////////////////

  case class ServerBinding(localAddress: InetSocketAddress,
                           connectionStream: Producer[IncomingConnection])

  case class IncomingConnection(remoteAddress: InetSocketAddress,
                                requestStream: Producer[HttpRequest],
                                responseStream: Consumer[HttpResponse])

  val Unbound = Tcp.Unbound

  class ConnectionException(message: String) extends RuntimeException(message)

  class ConnectionAttemptFailedException(val host: String, val port: Int) extends ConnectionException(s"Connection attempt to $host:$port failed")

  class RequestTimeoutException(val request: HttpRequest, message: String) extends ConnectionException(message)
}

class HttpExt(system: ExtendedActorSystem) extends akka.io.IO.Extension {
  val Settings = new Settings(system.settings.config getConfig "spray.can")
  class Settings private[HttpExt] (config: Config) {
    val ManagerDispatcher = config getString "manager-dispatcher"
    val SettingsGroupDispatcher = config getString "settings-group-dispatcher"
    val HostConnectorDispatcher = config getString "host-connector-dispatcher"
    val ListenerDispatcher = config getString "listener-dispatcher"
    val ConnectionDispatcher = config getString "connection-dispatcher"
  }

  val manager = system.actorOf(
    props = Props(new HttpManager(Settings)) withDispatcher Settings.ManagerDispatcher,
    name = "IO-HTTP")
}