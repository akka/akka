/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import scala.util.{ Failure, Success }
import scala.concurrent.duration._
import akka.actor._
import akka.http.engine.client._
import akka.http.engine.server.{ HttpServerPipeline, ServerSettings }
import akka.io.IO
import akka.pattern.ask
import akka.stream.FlowMaterializer
import akka.stream.io.StreamTcp
import akka.stream.scaladsl.Flow
import akka.util.Timeout

/**
 * INTERNAL API
 *
 * The gateway actor into the low-level HTTP layer.
 */
private[http] class HttpManager(httpSettings: HttpExt#Settings) extends Actor with ActorLogging {
  import context.dispatcher

  private[this] var clientPipelines = Map.empty[ClientConnectionSettings, HttpClientPipeline]

  def receive = {
    case connect @ Http.Connect(remoteAddress, localAddress, options, clientConnectionSettings, materializerSettings) ⇒
      log.debug("Attempting connection to {}", remoteAddress)
      val commander = sender()
      val effectiveSettings = ClientConnectionSettings(clientConnectionSettings)

      val tcpConnect = StreamTcp.Connect(remoteAddress, localAddress, materializerSettings, options,
        effectiveSettings.connectingTimeout, effectiveSettings.idleTimeout)
      val askTimeout = Timeout(effectiveSettings.connectingTimeout + 5.seconds) // FIXME: how can we improve this?
      val tcpConnectionFuture = IO(StreamTcp)(context.system).ask(tcpConnect)(askTimeout)
      tcpConnectionFuture onComplete {
        case Success(tcpConn: StreamTcp.OutgoingTcpConnection) ⇒
          val pipeline = clientPipelines.getOrElse(effectiveSettings, {
            val pl = new HttpClientPipeline(effectiveSettings, log)(FlowMaterializer(materializerSettings))
            clientPipelines = clientPipelines.updated(effectiveSettings, pl)
            pl
          })
          commander ! pipeline(tcpConn)

        case Failure(error) ⇒
          log.debug("Could not connect to {} due to {}", remoteAddress, error)
          commander ! Status.Failure(new Http.ConnectionAttemptFailedException(remoteAddress))

        case x ⇒ throw new IllegalStateException("Unexpected response to `Connect` from StreamTcp: " + x)
      }

    case Http.Bind(endpoint, backlog, options, serverSettings, materializerSettings) ⇒
      log.debug("Binding to {}", endpoint)
      val commander = sender()
      val effectiveSettings = ServerSettings(serverSettings)
      val tcpBind = StreamTcp.Bind(endpoint, materializerSettings, backlog, options)
      val askTimeout = Timeout(effectiveSettings.bindTimeout + 5.seconds) // FIXME: how can we improve this?
      val tcpServerBindingFuture = IO(StreamTcp)(context.system).ask(tcpBind)(askTimeout)
      tcpServerBindingFuture onComplete {
        case Success(tcpServerBinding @ StreamTcp.TcpServerBinding(localAddress, connectionStream)) ⇒
          log.info("Bound to {}", endpoint)
          implicit val materializer = FlowMaterializer()
          val httpServerPipeline = new HttpServerPipeline(effectiveSettings, log)
          val httpConnectionStream = Flow(connectionStream)
            .map(httpServerPipeline)
            .toPublisher()
          commander ! Http.ServerBinding(localAddress, httpConnectionStream, tcpServerBinding)

        case Failure(error) ⇒
          log.warning("Bind to {} failed due to {}", endpoint, error)
          commander ! Status.Failure(Http.BindFailedException)

        case x ⇒ throw new IllegalStateException("Unexpected response to `Bind` from StreamTcp: " + x)
      }
  }
}

private[http] object HttpManager {
  def props(httpSettings: HttpExt#Settings) =
    Props(classOf[HttpManager], httpSettings) withDispatcher httpSettings.ManagerDispatcher
}