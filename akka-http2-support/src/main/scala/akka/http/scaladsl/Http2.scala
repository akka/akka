/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.Done
import akka.NotUsed
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.dispatch.ExecutionContexts
import akka.event.LoggingAdapter
import akka.http.impl.engine.http2.{ AlpnSwitch, Http2Blueprint, WrappedSslContextSPI }
import akka.http.impl.engine.server.HttpAttributes
import akka.http.impl.util.LogByteStringTools.logTLSBidiBySetting
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.Fusing
import akka.stream.Materializer
import akka.stream.TLSProtocol.SendBytes
import akka.stream.TLSProtocol.SessionBytes
import akka.stream.TLSProtocol.SslTlsInbound
import akka.stream.TLSProtocol.SslTlsOutbound
import akka.stream.TLSRole
import akka.stream.scaladsl.BidiFlow
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Tcp
import akka.util.ByteString
import com.typesafe.config.Config

import scala.concurrent.Future
import scala.util.control.NonFatal

/** Entry point for Http/2 server */
class Http2Ext(private val config: Config)(implicit val system: ActorSystem) extends akka.actor.Extension {
  // FIXME: won't having the same package as top-level break osgi?

  private[this] final val DefaultPortForProtocol = -1 // any negative value

  val http = Http(system)

  def bindAndHandleAsync(
    handler:   HttpRequest ⇒ Future[HttpResponse],
    interface: String, port: Int = DefaultPortForProtocol,
    httpsContext: HttpsConnectionContext,
    settings:     ServerSettings         = ServerSettings(system),
    parallelism:  Int                    = 1,
    log:          LoggingAdapter         = system.log)(implicit fm: Materializer): Future[ServerBinding] = {
    // TODO: split up similarly to what `Http` does into `serverLayer`, `bindAndHandle`, etc.

    // automatically preserves association between request and response by setting the right headers, can use mapAsyncUnordered

    val effectivePort = if (port >= 0) port else 443

    val unwrapTls: BidiFlow[ByteString, SslTlsOutbound, SslTlsInbound, ByteString, NotUsed] =
      BidiFlow.fromFlows(Flow[ByteString].map(SendBytes), Flow[SslTlsInbound].collect {
        case SessionBytes(_, bytes) ⇒ bytes
      })

    def http2Layer(): BidiFlow[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest, NotUsed] =
      Http2Blueprint.serverStack() atop
        unwrapTls atop
        logTLSBidiBySetting("server-plain-text", settings.logUnencryptedNetworkBytes)

    // Flow is not reusable because we need a side-channel to transport the protocol
    // chosen by ALPN from the SSLEngine to the switching stage
    def serverLayer(): BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, NotUsed] = {
      // Mutable cell to transport the chosen protocol from the SSLEngine to
      // the switch stage.
      // Doesn't need to be volatile because there's a happens-before relationship (enforced by memory barriers)
      // between the SSL handshake and sending out the first SessionBytes, and receiving the first SessionBytes
      // and reading out the variable.
      var chosenProtocol: Option[String] = None
      def setChosenProtocol(protocol: String): Unit =
        if (chosenProtocol.isEmpty) chosenProtocol = Some(protocol)
        else throw new IllegalStateException("ChosenProtocol was set twice. Http2.serverLayer is not reusable.")
      def getChosenProtocol(): String = chosenProtocol.getOrElse("h1") // default to http/1, e.g. when ALPN jar is missing

      val wrappedContext = WrappedSslContextSPI.wrapContext(httpsContext, setChosenProtocol)
      val tls = http.sslTlsStage(wrappedContext, TLSRole.server)

      AlpnSwitch(getChosenProtocol, Http().serverLayer(), http2Layer()) atop
        tls
    }

    // Not reusable, see above.
    def fullLayer(): Flow[ByteString, ByteString, Future[Done]] = Flow.fromGraph(Fusing.aggressive(
      Flow[HttpRequest]
        .watchTermination()(Keep.right)
        // FIXME: parallelism should maybe kept in track with SETTINGS_MAX_CONCURRENT_STREAMS so that we don't need
        // to buffer requests that cannot be handled in parallel
        .via(Http2Blueprint.handleWithStreamIdHeader(parallelism)(handler)(system.dispatcher))
        .joinMat(serverLayer())(Keep.left)))

    val connections = Tcp().bind(interface, effectivePort, settings.backlog, settings.socketOptions, halfClose = false, settings.timeouts.idleTimeout)

    connections.mapAsyncUnordered(settings.maxConnections) {
      case incoming: Tcp.IncomingConnection ⇒
        try {
          val layer =
            if (settings.remoteAddressHeader) fullLayer().addAttributes(HttpAttributes.remoteAddress(Some(incoming.remoteAddress)))
            else fullLayer()

          layer.joinMat(incoming.flow)(Keep.left)
            .run().recover {
              // Ignore incoming errors from the connection as they will cancel the binding.
              // As far as it is known currently, these errors can only happen if a TCP error bubbles up
              // from the TCP layer through the HTTP layer to the Http.IncomingConnection.flow.
              // See https://github.com/akka/akka/issues/17992
              case NonFatal(ex) ⇒
                Done
            }(ExecutionContexts.sameThreadExecutionContext)
        } catch {
          case NonFatal(e) ⇒
            log.error(e, "Could not materialize handling flow for {}", incoming)
            throw e
        }
    }.mapMaterializedValue {
      _.map(tcpBinding ⇒ ServerBinding(tcpBinding.localAddress)(() ⇒ tcpBinding.unbind()))(fm.executionContext)
    }.to(Sink.ignore).run()
  }

}

object Http2 extends ExtensionId[Http2Ext] with ExtensionIdProvider {
  def apply()(implicit system: ActorSystem): Http2Ext = super.apply(system)
  def lookup(): ExtensionId[_ <: Extension] = Http2
  def createExtension(system: ExtendedActorSystem): Http2Ext =
    new Http2Ext(system.settings.config getConfig "akka.http")(system)
}
