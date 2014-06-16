/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import java.net.InetSocketAddress
import com.typesafe.config.Config
import org.reactivestreams.api.{ Producer, Consumer }
import scala.collection.immutable
import akka.io.Inet
import akka.stream.MaterializerSettings
import akka.http.client.{ HttpClientProcessor, ClientConnectionSettings }
import akka.http.server.ServerSettings
import akka.http.model.{ HttpResponse, HttpRequest, japi }
import akka.http.util._
import akka.actor._

object Http extends ExtensionKey[HttpExt] {

  /**
   * Command that can be sent to `IO(Http)` to trigger the setup of an HTTP client facility at
   * a certain API level (connection, host or request).
   * The HTTP layer will respond with an `Http.OutgoingChannel` reply (or `Status.Failure`).
   * The sender `ActorRef`of this response can then be sent `HttpRequest` instances to which
   * it will respond with `HttpResponse` instances (or `Status.Failure`).
   */
  sealed trait SetupOutgoingChannel

  final case class Connect(remoteAddress: InetSocketAddress,
                           localAddress: Option[InetSocketAddress],
                           options: immutable.Traversable[Inet.SocketOption],
                           settings: Option[ClientConnectionSettings],
                           materializerSettings: MaterializerSettings) extends SetupOutgoingChannel
  object Connect {
    def apply(host: String, port: Int = 80,
              localAddress: Option[InetSocketAddress] = None,
              options: immutable.Traversable[Inet.SocketOption] = Nil,
              settings: Option[ClientConnectionSettings] = None,
              materializerSettings: MaterializerSettings = MaterializerSettings()): Connect =
      apply(new InetSocketAddress(host, port), localAddress, options, settings, materializerSettings)
  }

  // PREVIEW OF COMING API HERE:
  //
  //  case class SetupHostConnector(host: String, port: Int = 80,
  //                                options: immutable.Traversable[Inet.SocketOption] = Nil,
  //                                settings: Option[HostConnectorSettings] = None,
  //                                connectionType: ClientConnectionType = ClientConnectionType.AutoProxied,
  //                                defaultHeaders: immutable.Seq[HttpHeader] = Nil) extends SetupOutgoingChannel {
  //    private[http] def normalized(implicit refFactory: ActorRefFactory) =
  //      if (settings.isDefined) this
  //      else copy(settings = Some(HostConnectorSettings(actorSystem)))
  //  }
  //  object SetupHostConnector {
  //    def apply(host: String, port: Int, sslEncryption: Boolean)(implicit refFactory: ActorRefFactory): SetupHostConnector =
  //      apply(host, port, sslEncryption).normalized
  //  }
  //  sealed trait ClientConnectionType
  //  object ClientConnectionType {
  //    object Direct extends ClientConnectionType
  //    object AutoProxied extends ClientConnectionType
  //    final case class Proxied(proxyHost: String, proxyPort: Int) extends ClientConnectionType
  //  }
  //
  //  case object SetupRequestChannel extends SetupOutgoingChannel

  sealed trait OutgoingChannel {
    def processor[T]: HttpClientProcessor[T]
  }

  /**
   * An `OutgoingHttpChannel` with a single outgoing HTTP connection as the underlying transport.
   */
  final case class OutgoingConnection(remoteAddress: InetSocketAddress,
                                      localAddress: InetSocketAddress,
                                      untypedProcessor: HttpClientProcessor[Any]) extends OutgoingChannel {
    def processor[T] = untypedProcessor.asInstanceOf[HttpClientProcessor[T]]
  }

  // PREVIEW OF COMING API HERE:
  //
  //  /**
  //   * An `OutgoingHttpChannel` with a connection pool to a specific host/port as the underlying transport.
  //   */
  //  final case class HostChannel(host: String, port: Int,
  //                               untypedProcessor: HttpClientProcessor[Any]) extends OutgoingChannel {
  //    def processor[T] = untypedProcessor.asInstanceOf[HttpClientProcessor[T]]
  //  }
  //
  //  /**
  //   * A general `OutgoingHttpChannel` with connection pools to all possible host/port combinations
  //   * as the underlying transport.
  //   */
  //  final case class RequestChannel(untypedProcessor: HttpClientProcessor[Any]) extends OutgoingChannel {
  //    def processor[T] = untypedProcessor.asInstanceOf[HttpClientProcessor[T]]
  //  }

  final case class Bind(endpoint: InetSocketAddress,
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

  final case class ServerBinding(localAddress: InetSocketAddress,
                                 connectionStream: Producer[IncomingConnection]) extends model.japi.ServerBinding {
    /** Java API */
    def getConnectionStream: Producer[japi.IncomingConnection] = connectionStream.asInstanceOf[Producer[japi.IncomingConnection]]
  }

  final case class IncomingConnection(remoteAddress: InetSocketAddress,
                                      requestProducer: Producer[HttpRequest],
                                      responseConsumer: Consumer[HttpResponse]) extends model.japi.IncomingConnection {
    /** Java API */
    def getRequestProducer: Producer[japi.HttpRequest] = requestProducer.asInstanceOf[Producer[japi.HttpRequest]]
    /** Java API */
    def getResponseConsumer: Consumer[japi.HttpResponse] = responseConsumer.asInstanceOf[Consumer[japi.HttpResponse]]
  }

  case object BindFailedException extends SingletonException

  class ConnectionException(message: String) extends RuntimeException(message)

  class ConnectionAttemptFailedException(val endpoint: InetSocketAddress) extends ConnectionException(s"Connection attempt to $endpoint failed")

  class RequestTimeoutException(val request: HttpRequest, message: String) extends ConnectionException(message)
}

class HttpExt(system: ExtendedActorSystem) extends akka.io.IO.Extension {
  val Settings = new Settings(system.settings.config getConfig "akka.http")
  class Settings private[HttpExt] (config: Config) {
    val ManagerDispatcher = config getString "manager-dispatcher"
  }

  val manager = system.actorOf(props = HttpManager.props(Settings), name = "IO-HTTP")
}